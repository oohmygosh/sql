/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.service;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.data.model.ExprValue;
import org.opensearch.sql.executor.ExecutionEngine.Schema;
import org.opensearch.sql.executor.QueryCancellation;
import org.opensearch.sql.executor.QueryRowConsumer;
import org.opensearch.sql.executor.QueryType;
import org.opensearch.sql.job.QueryResult;
import org.opensearch.sql.job.QueryRunner;
import org.opensearch.sql.protocol.response.format.CsvResponseFormatter;

/** A single export, with bounded row buffering and file ownership independent of job metadata. */
final class QueryExportRunner implements QueryRunner {
  private final UnifiedQueryService queries;
  private final String source;
  private final QueryType language;
  private final String query;
  private final ExecutorService executor;
  private final Path directory;
  private final long maxFileBytes;
  private final QueryCancellation cancellation = new QueryCancellation();
  private final CompletableFuture<QueryResult> completion = new CompletableFuture<>();
  private Future<?> worker;
  private boolean started;
  private boolean rejected;
  private boolean disposed;
  private Path file;
  private volatile Progress progress = new Progress(0, 0);

  QueryExportRunner(
      UnifiedQueryService queries,
      String source,
      QueryType language,
      String query,
      ExecutorService executor,
      Path directory,
      long maxFileBytes) {
    this.queries = queries;
    this.source = source;
    this.language = language;
    this.query = query;
    this.executor = executor;
    this.directory = directory;
    this.maxFileBytes = maxFileBytes;
  }

  @Override
  public synchronized CompletionStage<QueryResult> run() {
    if (started) throw new IllegalStateException("QueryExportRunner is single-use");
    started = true;
    if (!completion.isDone()) {
      try {
        worker = executor.submit(this::export);
      } catch (RejectedExecutionException e) {
        rejected = true;
        throw e;
      }
    }
    return completion.minimalCompletionStage();
  }

  private void export() {
    long start = System.nanoTime();
    try {
      Path target;
      BufferedOutputStream output;
      synchronized (this) {
        cancellation.check();
        if (disposed) throw new CancellationException();
        file = Files.createTempFile(directory, "export-", ".csv");
        target = file;
        output = new BufferedOutputStream(Files.newOutputStream(target), 64 * 1024);
      }
      try (output) {
        var formatter = new CsvResponseFormatter();
        var sink =
            new QueryRowConsumer() {
              private long rows;
              private long bytes;
              private long lastFlush = System.nanoTime();

              public void schema(Schema schema) throws IOException {
                write(formatter.formatHeader(schema));
                flush();
              }

              public void row(ExprValue row) throws IOException {
                cancellation.check();
                write(System.lineSeparator() + formatter.formatRow(row));
                rows++;
                if (rows % 1000 == 0 || System.nanoTime() - lastFlush > 250_000_000L) flush();
              }

              private void write(String value) throws IOException {
                byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
                if (encoded.length > maxFileBytes - bytes) {
                  throw new IllegalStateException(
                      "Export exceeded max-file-bytes (" + maxFileBytes + ")");
                }
                output.write(encoded);
                bytes += encoded.length;
              }

              private void flush() throws IOException {
                output.flush();
                progress = new Progress(rows, bytes);
                lastFlush = System.nanoTime();
              }
            };
        queries.stream(source, language, query, sink, cancellation);
        sink.flush();
      }
      cancellation.check();
      synchronized (this) {
        if (disposed) throw new CancellationException();
        progress = new Progress(progress.exportedRows(), Files.size(target));
        completion.complete(
            new QueryResult.Export(
                progress.exportedRows(),
                progress.fileSizeBytes(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)));
      }
    } catch (Exception | Error e) {
      try {
        deleteFile();
      } catch (IOException cleanup) {
        e.addSuppressed(cleanup);
      }
      completion.completeExceptionally(e);
      if (e instanceof Error error) throw error;
    }
  }

  Progress progress() {
    return progress;
  }

  synchronized boolean rejected() {
    return rejected;
  }

  synchronized InputStream openFile() throws IOException {
    if (disposed || file == null) throw new NoSuchFileException("Export expired");
    return Files.newInputStream(file);
  }

  @Override
  public synchronized void cancel() {
    cancellation.cancel();
    if (worker != null) worker.cancel(true);
    completion.cancel(false);
  }

  synchronized void dispose() throws IOException {
    disposed = true;
    cancel();
    deleteFile();
  }

  private synchronized void deleteFile() throws IOException {
    if (file != null) {
      Files.deleteIfExists(file);
      file = null;
    }
    progress = new Progress(progress.exportedRows(), 0);
  }

  record Progress(long exportedRows, long fileSizeBytes) {}
}
