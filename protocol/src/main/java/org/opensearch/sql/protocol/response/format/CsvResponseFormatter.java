/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.protocol.response.format;

import java.util.List;
import org.opensearch.sql.data.model.ExprValue;
import org.opensearch.sql.executor.ExecutionEngine.Schema;
import org.opensearch.sql.protocol.response.QueryResult;

/** Response formatter to format response to csv format. */
public class CsvResponseFormatter implements ResponseFormatter<QueryResult> {
  public static final String CONTENT_TYPE = "plain/text; charset=UTF-8";
  private final String separator;
  private final boolean sanitize;

  public CsvResponseFormatter() {
    this(",", true);
  }

  public CsvResponseFormatter(boolean sanitize) {
    this(",", sanitize);
  }

  public CsvResponseFormatter(String separator, boolean sanitize) {
    this.separator = separator;
    this.sanitize = sanitize;
  }

  @Override
  public String format(QueryResult response) {
    FlatResponseBase flatResponse;
    if (sanitize) {
      flatResponse = new FlatResponseWithSanitizer(response, separator);
    } else {
      flatResponse = new FlatResponseBase(response, separator);
    }
    return flatResponse.format();
  }

  /** Formats just the header, including the same escaping and sanitization as buffered output. */
  public String formatHeader(Schema schema) {
    return format(new QueryResult(schema, List.of()));
  }

  /** Formats one row without accumulating the rest of the result set. */
  public String formatRow(ExprValue row) {
    QueryResult result = new QueryResult(new Schema(List.of()), List.of(row));
    FlatResponseBase flat =
        sanitize
            ? new FlatResponseWithSanitizer(result, separator)
            : new FlatResponseBase(result, separator);
    return flat.getDataLines().getFirst();
  }

  @Override
  public String format(Throwable t) {
    return ErrorFormatter.prettyFormat(t);
  }

  @Override
  public String contentType() {
    return CONTENT_TYPE;
  }
}
