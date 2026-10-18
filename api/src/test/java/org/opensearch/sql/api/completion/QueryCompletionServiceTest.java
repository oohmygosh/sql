/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api.completion;

import static org.junit.Assert.*;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.Test;
import org.opensearch.sql.api.completion.CompletionResponse.Item;
import org.opensearch.sql.api.completion.CompletionResponse.TextEdit;

public class QueryCompletionServiceTest {
  private final CompletionMetadata metadata =
      new CompletionMetadata() {
        public String resolveSource(String source) {
          if (source == null || source.equals("logs")) return "logs";
          throw new IllegalArgumentException("Unknown data source");
        }

        public List<Relation> tables(
            String source, List<String> namespace, String prefix, int limit) {
          throw new AssertionError("Syntax completion must not query the catalog");
        }

        public List<Field> fields(String source, RelationRef relation) {
          throw new AssertionError("Syntax completion must not query the catalog");
        }
      };

  private LanguageCompletionProvider provider(String language, int count) {
    return new LanguageCompletionProvider() {
      public String language() {
        return language;
      }

      public CompletionResponse complete(CompletionRequest request, CompletionMetadata ignored) {
        assertEquals("logs", request.source());
        return new CompletionResponse(
            request.documentVersion(),
            IntStream.range(0, count)
                .mapToObj(
                    i ->
                        new Item(
                            language + i,
                            "keyword",
                            language,
                            new TextEdit(request.caret(), request.caret(), "next "),
                            null))
                .toList(),
            false,
            null);
      }
    };
  }

  @Test
  public void nativeExtensionCandidatesComeFromTheSelectedBackend() {
    CompletionMetadata enabled =
        new CompletionMetadata() {
          public String resolveSource(String source) {
            return "logs";
          }

          public java.util.Set<String> capabilities(String source) {
            return java.util.Set.of("machine_learning");
          }

          public List<String> parameterNames(String source, String capability) {
            return List.of("backend_registered_option");
          }

          public List<Relation> tables(
              String source, List<String> namespace, String prefix, int limit) {
            return List.of();
          }

          public List<Field> fields(String source, RelationRef relation) {
            throw new AssertionError("ML argument names do not need table fields");
          }
        };
    var service = new QueryCompletionService(enabled, List.of(new PplCompletionProvider()));
    String menu = "source=events | ";
    assertTrue(
        service
            .complete(new CompletionRequest("ppl", "logs", menu, menu.length(), 1, "input"))
            .items()
            .stream()
            .anyMatch(item -> item.label().equals("ml")));
    String arguments = menu + "ml ";
    assertTrue(
        service
            .complete(
                new CompletionRequest("ppl", "logs", arguments, arguments.length(), 2, "input"))
            .items()
            .stream()
            .anyMatch(item -> item.label().equals("backend_registered_option")));
  }

  @Test
  public void dispatchesRegisteredLanguagesWithTheSameProtocol() {
    var service =
        new QueryCompletionService(metadata, List.of(provider("ppl", 1), provider("sql", 1)));
    assertEquals(List.of("ppl", "sql"), service.languages());
    var response = service.complete(new CompletionRequest("SQL", null, "SELECT ", 7, 23, "manual"));
    assertEquals(23, response.documentVersion());
    assertEquals("sql0", response.items().getFirst().label());
    assertEquals(new TextEdit(7, 7, "next "), response.items().getFirst().textEdit());
  }

  @Test
  public void rejectsUnsupportedLanguageAndUnknownSource() {
    var service = new QueryCompletionService(metadata, List.of(provider("ppl", 1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.complete(new CompletionRequest("sql", "logs", "", 0, 1, "input")));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.complete(new CompletionRequest("ppl", "missing", "", 0, 1, "input")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new QueryCompletionService(metadata, List.of(provider("ppl", 1), provider("ppl", 1))));
  }

  @Test
  public void boundsResultsAndRejectsInvalidEditorOffsets() {
    var service = new QueryCompletionService(metadata, List.of(provider("ppl", 101)));
    var response = service.complete(new CompletionRequest("ppl", "logs", "", 0, 1, "input"));
    assertEquals(100, response.items().size());
    assertTrue(response.incomplete());
    assertThrows(
        IllegalArgumentException.class,
        () -> new CompletionRequest("ppl", "logs", "😀", 1, 0, "input"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CompletionRequest("ppl", "logs", "a".repeat(16_385), 0, 0, "input"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CompletionRequest("ppl", "logs", "", -1, 0, "input"));
  }
}
