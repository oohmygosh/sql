/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api.completion;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.opensearch.sql.api.completion.CompletionMetadata.Field;
import org.opensearch.sql.api.completion.CompletionMetadata.Relation;
import org.opensearch.sql.api.completion.CompletionMetadata.RelationRef;
import org.opensearch.sql.api.completion.CompletionResponse.Item;

public class PplCompletionProviderTest {
  private final PplCompletionProvider provider = new PplCompletionProvider();
  private final List<RelationRef> lookups = new ArrayList<>();
  private CompletionMetadata metadata;

  @Before
  public void setup() {
    metadata =
        new CompletionMetadata() {
          @Override
          public String resolveSource(String source) {
            return source;
          }

          @Override
          public List<Relation> tables(
              String source, List<String> namespace, String prefix, int limit) {
            return List.of("logevent-*", "orders").stream()
                .filter(name -> name.startsWith(prefix))
                .map(name -> new Relation(namespace, name, "table"))
                .toList();
          }

          @Override
          public List<Field> fields(String source, RelationRef relation) {
            lookups.add(relation);
            return relation.name().equals("orders")
                ? List.of(new Field("order_id", "string"), new Field("amount", "double"))
                : List.of(
                    new Field("status", "integer"),
                    new Field("level", "string"),
                    new Field("message", "string"),
                    new Field("latency", "double"),
                    new Field("timestamp", "timestamp"),
                    new Field("enabled", "boolean"),
                    new Field("http", "row", List.of("http")),
                    new Field("http.status", "integer", List.of("http", "status")),
                    new Field("literal.dot", "string", List.of("literal.dot")));
          }
        };
  }

  @Test
  public void openingUsesOnlySupportedSearchEntrypoints() {
    assertEquals(List.of("source", "search", "index"), labels(""));
    assertEquals(List.of("source"), labels("so"));
    assertEquals(List.of("source", "index"), labels("search"));
    assertEquals(List.of("="), labels("source"));
    assertEquals(List.of("="), labels("search index "));
    assertTrue(lookups.isEmpty());
  }

  @Test
  public void acceptChainReopensTheNextContext() {
    String text = accept("", "source");
    text = accept(text, "=");
    assertEquals(List.of("logevent-*", "orders"), labels(text));
    text = accept(text, "logevent-*");
    text = accept(text, "|");
    assertTrue(labels(text).containsAll(List.of("where", "stats", "fields")));
    text = accept(text, "where");
    text = accept(text, "status");
    assertTrue(labels(text).containsAll(List.of("=", "!=", ">", ">=", "<", "<=")));
    text = accept(text, "=");
    assertTrue(labels(text).isEmpty());
    text += "200";
    assertTrue(labels(text).containsAll(List.of("and", "xor", "or", "|")));
    text = accept(text, "and");
    assertTrue(labels(text).contains("level"));
    assertTrue(text.startsWith("source = `logevent-*` | where `status` = 200 and "));
  }

  @Test
  public void completeTablesOfferPipeWithoutWhitespace() {
    assertEquals(List.of("|"), labels("source=orders"));
    assertEquals(List.of("|"), labels("source=`logevent-*`"));
    assertEquals(List.of("|"), labels("source=`logevent-*` "));
    assertTrue(lookups.isEmpty());
    assertEquals(List.of("orders"), labels("index=or"));
    assertFalse(labels("source=").contains("false"));
  }

  @Test
  public void fieldsFollowTheWrittenTable() {
    assertEquals(List.of("order_id", "amount"), fieldLabels("source=orders | where "));
    assertEquals(new RelationRef(List.of(), "orders"), lookups.get(0));
    assertFalse(fieldLabels("source=`logevent-*` | where ").contains("order_id"));
  }

  @Test
  public void fieldsPreserveNamespaces() {
    fieldLabels("source=`database`.`orders` | fields ");
    assertEquals(new RelationRef(List.of("database"), "orders"), lookups.get(0));
  }

  @Test
  public void BooleanComparisonSuggestsBooleanValues() {
    assertEquals(
        List.of("true", "false"),
        labels("source=orders | eval enabled=true | where enabled = ").subList(0, 2));
  }

  @Test
  public void actualBooleanTypeControlsValueHints() {
    assertEquals(
        List.of("true", "false"), labels("source=`logevent-*` | where enabled = ").subList(0, 2));
    assertTrue(
        labels("source=`logevent-*` | where enabled = true")
            .containsAll(List.of("and", "xor", "or", "|")));
  }

  @Test
  public void projectionAndExclusionChangeDownstreamFields() {
    assertEquals(List.of("level"), fieldLabels("source=`logevent-*` | fields level | where "));
    assertFalse(fieldLabels("source=`logevent-*` | fields - message | where ").contains("message"));
    assertEquals(
        List.of("http.status"), fieldLabels("source=`logevent-*` | fields http.st* | where "));
    assertEquals(
        List.of("status", "level"),
        fieldLabels("source=`logevent-*` | table status level | where "));
  }

  @Test
  public void renameReplacesAndEvalAddsFields() {
    List<String> renamed = fieldLabels("source=`logevent-*` | rename status as state | where ");
    assertTrue(renamed.contains("state"));
    assertFalse(renamed.contains("status"));
    List<String> evaluated = fieldLabels("source=`logevent-*` | eval result=latency+1 | fields ");
    assertTrue(evaluated.containsAll(List.of("latency", "result")));
  }

  @Test
  public void aggregatesReplaceSchemaButWindowAggregatesKeepIt() {
    assertEquals(
        List.of("level", "total"),
        fieldLabels("source=`logevent-*` | stats count() as total by level | fields "));
    assertTrue(
        fieldLabels("source=`logevent-*` | eventstats count() as total by level | fields ")
            .containsAll(List.of("status", "level", "total")));
    assertTrue(
        fieldLabels("source=`logevent-*` | streamstats count() as total by level | fields ")
            .containsAll(List.of("status", "level", "total")));
  }

  @Test
  public void aggregateArgumentsAndGroupingAreContextual() {
    assertTrue(labels("source=`logevent-*` | stats ").contains("avg"));
    assertFalse(labels("source=`logevent-*` | stats ").contains("status"));
    assertTrue(fieldLabels("source=`logevent-*` | stats avg(").contains("latency"));
    assertTrue(
        labels("source=`logevent-*` | stats avg(latency").containsAll(List.of(")", "+", "*")));
    assertFalse(labels("source=`logevent-*` | stats avg(latency").contains(","));
    assertTrue(
        labels("source=`logevent-*` | stats avg(latency)").containsAll(List.of("as", "by", "|")));
    assertTrue(fieldLabels("source=`logevent-*` | stats avg(latency) by ").contains("level"));
    assertTrue(labels("source=`logevent-*` | stats count(").contains(")"));
  }

  @Test
  public void spanGroupingCompletesContinuouslyAcrossAggregateCommands() {
    for (String command : List.of("stats", "eventstats", "streamstats")) {
      String text = "source=`logevent-*` | " + command + " count() as total by ";
      assertTrue(text, labels(text).contains("span"));
      text = accept(text, "span");
      assertTrue(text, fieldLabels(text).contains("timestamp"));
      text = accept(text, "timestamp");
      assertTrue(text, labels(text).contains(","));
      text = accept(text, ",");
      assertTrue(text, labels(text).containsAll(List.of("1s", "1m", "1h", "1d")));
      text = accept(text, "1h");
      assertTrue(text, labels(text).contains(")"));
      text = accept(text, ")");
      assertTrue(text, labels(text).containsAll(List.of("as", "|", ",")));
    }
  }

  @Test
  public void spanGroupingSuggestsNumericAndPartiallyTypedTimeIntervals() {
    assertTrue(
        labels("source=orders | stats count() by span(amount, ")
            .containsAll(List.of("1", "10", "100")));
    assertTrue(
        labels("source=`logevent-*` | stats count() by span(timestamp, 30")
            .containsAll(List.of("30s", "30m", "30h", "30d")));
    assertTrue(
        labels("source=`logevent-*` | stats avg(latency) by span(1")
            .containsAll(List.of("1s", "1m", "1h", "1d")));
    assertTrue(
        labels("source=`logevent-*` | stats avg(latency) by span(30")
            .containsAll(List.of("30s", "30m", "30h", "30d")));
    assertTrue(labels("source=`logevent-*` | stats count() by level, ").contains("span"));
  }

  @Test
  public void spanGroupingPropagatesOnlyActualOutputFields() {
    String source = "source=`logevent-*` | ";
    CompletionResponse named =
        complete(source + "stats count() as total by span(timestamp,1h) as hour, level | fields ");
    assertFalse(named.incomplete());
    assertEquals(List.of("hour", "level", "total"), fieldLabels(named));
    assertEquals(
        List.of("span(`timestamp`,1h)", "total"),
        fieldLabels(source + "stats count() as total by span(`timestamp`, 1h) | fields "));
    assertEquals(
        List.of("hour", "total"),
        fieldLabels(source + "stats count() as total by span(1h) as hour | fields "));
    assertTrue(
        labels(source + "stats count() by span(timestamp,1h) as hour | bin hour span=")
            .containsAll(List.of("1s", "1m", "1h", "1d")));
    assertTrue(
        labels("source=orders | stats count() by span(amount,10) as bucket | bin bucket span=")
            .containsAll(List.of("1", "10", "100")));
    assertTrue(
        complete(
                source
                    + "eval textTime='2026-10-18 12:00:00' | stats count() by"
                    + " span(textTime,1h) as hour | fields ")
            .items()
            .stream()
            .anyMatch(item -> item.label().equals("hour") && item.detail().equals("string")));
    for (String command : List.of("eventstats", "streamstats")) {
      CompletionResponse window =
          complete(source + command + " count() as total by span(timestamp,1h) as hour | fields ");
      assertFalse(command, window.incomplete());
      assertTrue(command, fieldLabels(window).containsAll(List.of("timestamp", "level", "total")));
      assertFalse(command, fieldLabels(window).contains("hour"));
    }
  }

  @Test
  public void completedCommandsOfferPipe() {
    for (String suffix :
        List.of(
            "head 10",
            "reverse",
            "fields level",
            "sort status",
            "eval result=10",
            "rename status as state"))
      assertTrue(suffix, labels("source=`logevent-*` | " + suffix).contains("|"));
  }

  @Test
  public void conditionConnectivesRestartFieldContext() {
    assertTrue(fieldLabels("source=`logevent-*` | where status=200 and ").contains("status"));
    assertTrue(fieldLabels("source=`logevent-*` | where status=200 or ").contains("level"));
    assertEquals(List.of("and"), labels("source=`logevent-*` | where status=200 a"));
    assertTrue(
        labels("source=`logevent-*` | where message='done'")
            .containsAll(List.of("and", "xor", "or", "|")));
  }

  @Test
  public void cursorBeforeLaterPipelineUsesOnlyEarlierSchema() {
    String before = "source=`logevent-*` | where ";
    CompletionResponse result = complete(before + " | stats count() as total", before.length());
    assertTrue(fieldLabels(result).contains("status"));
    assertFalse(fieldLabels(result).contains("total"));
  }

  @Test
  public void middleTokenReplacementPreservesTheSuffix() {
    String text = "source=`logevent-*` | where staXXX = 200";
    int caret = text.indexOf("staXXX") + 3;
    Item status =
        complete(text, caret).items().stream()
            .filter(item -> item.label().equals("status"))
            .findFirst()
            .orElseThrow();
    assertEquals("source=`logevent-*` | where `status` = 200", apply(text, status));
  }

  @Test
  public void nestedFieldsAndQuotedPrefixesReplaceOneIdentifier() {
    assertTrue(fieldLabels("source=`logevent-*` | where http.st").contains("http.status"));
    assertEquals(List.of("message"), fieldLabels("source=`logevent-*` | where `mess"));
  }

  @Test
  public void stringsAndCommentsDoNotSuggestFields() {
    for (String text :
        List.of(
            "source=orders // comment",
            "source=orders /* comment",
            "source=orders | where order_id='abc",
            "source=orders | where order_id=\"abc")) assertTrue(text, labels(text).isEmpty());
  }

  @Test
  public void unicodeOffsetsPreserveTextOutsideTheReplacement() {
    String text = "source=`logevent-*` | where message='😀' and staXXX=200";
    int caret = text.indexOf("staXXX") + 3;
    Item status =
        complete(text, caret).items().stream()
            .filter(item -> item.label().equals("status"))
            .findFirst()
            .orElseThrow();
    assertEquals("source=`logevent-*` | where message='😀' and `status`=200", apply(text, status));
  }

  @Test
  public void unsupportedPipelineSchemaIsExplicitAndNeverUsesOriginalFields() {
    CompletionResponse response = complete("source=`logevent-*` | top level | where ");
    assertTrue(response.incomplete());
    assertFalse(response.message().isBlank());
    assertTrue(fieldLabels(response).isEmpty());
  }

  @Test
  public void incompleteSourceDoesNotBorrowDefaultTableFields() {
    CompletionResponse response = complete("source= | where ");
    assertTrue(response.incomplete());
    assertTrue(fieldLabels(response).isEmpty());
  }

  @Test
  public void nestedSourceDoesNotLeakInnerFields() {
    CompletionResponse response = complete("source=[source=orders] | where ");
    assertTrue(response.incomplete());
    assertTrue(fieldLabels(response).isEmpty());
  }

  @Test
  public void deepInputIsBoundedBeforeParsing() {
    CompletionResponse response = complete("source=orders | where " + "(".repeat(65));
    assertTrue(response.incomplete());
    assertTrue(response.message().contains("嵌套"));
  }

  @Test
  public void metadataFailureIsDifferentFromAnEmptySchema() {
    metadata =
        new CompletionMetadata() {
          @Override
          public String resolveSource(String source) {
            return source;
          }

          @Override
          public List<Relation> tables(
              String source, List<String> namespace, String prefix, int limit) {
            throw new MetadataException("offline");
          }

          @Override
          public List<Field> fields(String source, RelationRef relation) {
            throw new MetadataException("offline");
          }
        };
    CompletionResponse response = complete("source=orders | where ");
    assertTrue(response.incomplete());
    assertTrue(response.message().contains("offline"));
  }

  @Test
  public void groupedConditionStillRequiresAComparisonAndClosesBeforePiping() {
    assertTrue(labels("source=`logevent-*` | where (status").contains("="));
    assertFalse(labels("source=`logevent-*` | where (status").contains(")"));
    assertTrue(labels("source=`logevent-*` | where (status=500").contains(")"));
    assertFalse(labels("source=`logevent-*` | where (status=500").contains("|"));
    assertTrue(
        labels("source=`logevent-*` | where (status=500 and level='error')")
            .containsAll(List.of("and", "xor", "or", "|")));
  }

  @Test
  public void metadataFailureDoesNotHideGrammarOnlySuggestions() {
    metadata =
        new CompletionMetadata() {
          @Override
          public String resolveSource(String source) {
            return source;
          }

          @Override
          public List<Relation> tables(
              String source, List<String> namespace, String prefix, int limit) {
            throw new MetadataException("offline");
          }

          @Override
          public List<Field> fields(String source, RelationRef relation) {
            throw new MetadataException("offline");
          }
        };
    assertTrue(labels("source=orders | ").contains("where"));
    assertEquals(List.of("from", "|"), labels("source=orders | head 10"));
    assertTrue(labels("source=orders | stats ").contains("count"));
  }

  @Test
  public void middleTableTokenIsReplacedInsteadOfInsertingAPipe() {
    String text = "source=ordersOLD | where order_id='a'";
    int caret = text.indexOf("ordersOLD") + "orders".length();
    CompletionResponse response = complete(text, caret);
    assertFalse(response.items().stream().anyMatch(item -> item.label().equals("|")));
    Item orders =
        response.items().stream()
            .filter(item -> item.label().equals("orders"))
            .findFirst()
            .orElseThrow();
    assertEquals("source=`orders` | where order_id='a'", apply(text, orders));
  }

  @Test
  public void sortDirectionCompletesAndCannotMixWithASign() {
    assertTrue(labels("source=`logevent-*` | sort status desc").contains("|"));
    assertTrue(labels("source=`logevent-*` | sort -status").contains("|"));
    assertFalse(labels("source=`logevent-*` | sort -status").contains("asc"));
    assertFalse(labels("source=`logevent-*` | sort +status").contains("desc"));
  }

  @Test
  public void qualifiedMiddleTokenDoesNotConsumeTheComparison() {
    String text = "source=`logevent-*` | where http.stXXX=200";
    int caret = text.indexOf("stXXX") + 2;
    Item item =
        complete(text, caret).items().stream()
            .filter(value -> value.label().equals("http.status"))
            .findFirst()
            .orElseThrow();
    assertEquals("source=`logevent-*` | where `http`.`status`=200", apply(text, item));
  }

  @Test
  public void structuredAndLiteralDottedFieldsUseDifferentIdentifierPaths() {
    CompletionResponse result = complete("source=`logevent-*` | where ");
    Item nested =
        result.items().stream()
            .filter(item -> item.label().equals("http.status"))
            .findFirst()
            .orElseThrow();
    Item literal =
        result.items().stream()
            .filter(item -> item.label().equals("literal.dot"))
            .findFirst()
            .orElseThrow();
    assertEquals("`http`.`status`", nested.textEdit().newText());
    assertEquals("`literal.dot`", literal.textEdit().newText());
  }

  @Test
  public void structureProjectionAndRenamePropagateDescendantPaths() {
    assertEquals(
        List.of("http", "http.status"), fieldLabels("source=`logevent-*` | fields http | where "));
    assertFalse(
        fieldLabels("source=`logevent-*` | fields - http | where ").contains("http.status"));
    assertTrue(fieldLabels("source=`logevent-*` | fields - http | where ").contains("literal.dot"));
    CompletionResponse result = complete("source=`logevent-*` | rename http as request | where ");
    assertFalse(fieldLabels(result).contains("http.status"));
    Item nested =
        result.items().stream()
            .filter(item -> item.label().equals("request.status"))
            .findFirst()
            .orElseThrow();
    assertEquals("`request`.`status`", nested.textEdit().newText());
  }

  @Test
  public void sourceAliasesResolveOnlyTheirOwnFields() {
    assertEquals(List.of("l.status"), fieldLabels("source=`logevent-*` as l | where l.st"));
    Item item = complete("source=`logevent-*` as l | where l.st").items().get(0);
    assertEquals("`l`.`status`", item.textEdit().newText());
    assertTrue(labels("source=`logevent-*` as l | where l.status").contains("="));
    assertTrue(fieldLabels("source=`logevent-*` as l | where other.st").isEmpty());
  }

  @Test
  public void acceptingAMiddleFieldDoesNotDuplicateExistingOperators() {
    String before = "source=`logevent-*` | where staXXX =200";
    int caret = before.indexOf("staXXX") + 3;
    Item status =
        complete(before, caret).items().stream()
            .filter(item -> item.label().equals("status"))
            .findFirst()
            .orElseThrow();
    String after = apply(before, status);
    int nextCaret = status.textEdit().from() + status.textEdit().newText().length();
    assertFalse(
        complete(after, nextCaret).items().stream().anyMatch(item -> item.label().equals("=")));
    assertTrue(complete("source = orders", "source".length()).items().isEmpty());
  }

  @Test
  public void identicalDisplayNamesKeepTheirDifferentPaths() {
    metadata =
        new CompletionMetadata() {
          @Override
          public String resolveSource(String source) {
            return source;
          }

          @Override
          public List<Relation> tables(
              String source, List<String> namespace, String prefix, int limit) {
            return List.of();
          }

          @Override
          public List<Field> fields(String source, RelationRef relation) {
            return List.of(
                new Field("http.status", "string", List.of("http.status")),
                new Field("http.status", "integer", List.of("http", "status")));
          }
        };
    CompletionResponse result = complete("source=orders | where http.st");
    assertEquals(2, result.items().size());
    assertEquals(
        List.of("`http.status`", "`http`.`status`"),
        result.items().stream().map(item -> item.textEdit().newText()).toList());
    CompletionResponse projected = complete("source=orders | fields `http.status` | where ");
    assertEquals(1, fieldLabels(projected).size());
    assertEquals("`http.status`", projected.items().get(0).textEdit().newText());
    CompletionResponse excluded = complete("source=orders | fields - http | where ");
    assertEquals(1, fieldLabels(excluded).size());
    assertEquals("`http.status`", excluded.items().get(0).textEdit().newText());
  }

  @Test
  public void expandedMappingsDoNotHideCommonFieldsBeforeTheResponseLimit() {
    metadata =
        new CompletionMetadata() {
          @Override
          public String resolveSource(String source) {
            return source;
          }

          @Override
          public List<Relation> tables(
              String source, List<String> namespace, String prefix, int limit) {
            return List.of();
          }

          @Override
          public List<Field> fields(String source, RelationRef relation) {
            List<Field> mapping = new ArrayList<>();
            for (int index = 0; index < 150; index++)
              mapping.add(new Field("extracted.field" + index, "string"));
            mapping.add(new Field("nested.value", "string", List.of("nested", "value")));
            mapping.add(new Field("_id", "string"));
            mapping.add(new Field("timestamp", "timestamp"));
            return mapping;
          }
        };
    QueryCompletionService service = new QueryCompletionService(metadata, List.of(provider));
    String text = "source=orders | where ";
    CompletionResponse response =
        service.complete(new CompletionRequest("ppl", "logs", text, text.length(), 42, "input"));
    assertEquals(100, response.items().size());
    assertTrue(response.incomplete());
    assertEquals("timestamp", response.items().get(0).label());
    assertEquals("_id", response.items().get(1).label());
    String prefixed = text + "extracted.field149";
    CompletionResponse filtered =
        service.complete(
            new CompletionRequest("ppl", "logs", prefixed, prefixed.length() - 1, 43, "input"));
    assertTrue(
        filtered.items().stream().anyMatch(item -> item.label().equals("extracted.field149")));
    assertFalse(filtered.incomplete());
  }

  @Test
  public void binGuidesFieldSpanEqualsTimeIntervalAndNextCommand() {
    String query = "source=`logevent-*` | ";
    assertTrue(labels(query).contains("bin"));
    query = accept(query, "bin");
    query = accept(query, "timestamp");
    assertTrue(labels(query).contains("span"));
    assertFalse(labels(query).contains("minspan"));
    query = accept(query, "span");
    assertEquals(List.of("="), labels(query));
    query = accept(query, "=");
    assertTrue(labels(query).containsAll(List.of("1s", "1m", "5m", "1h", "1d")));
    query = accept(query, "1h");
    assertEquals(List.of("aligntime", "as", "|"), labels(query));
    query = accept(query, "|");
    query = accept(query, "fields");
    assertTrue(fieldLabels(query).containsAll(List.of("timestamp", "message")));
    assertFalse(complete(query).incomplete());
  }

  @Test
  public void binCompletesPrefixesAndKeepsParameterSlotsSeparate() {
    String query = "source=`logevent-*` | bin timestamp ";
    assertEquals(List.of("span"), labels(query + "sp"));
    assertEquals(List.of("="), labels(query + "span"));
    assertTrue(labels(query + "span=30").containsAll(List.of("30s", "30m", "30h", "30d")));
    assertEquals(List.of("aligntime"), labels(query + "span=1h ali"));
    assertEquals(List.of("="), labels(query + "span=1h aligntime"));
    assertEquals(List.of("earliest", "latest", "'@d'"), labels(query + "span=1h aligntime="));
    assertEquals(List.of("as", "|"), labels(query + "span=1h aligntime=earliest "));
    assertFalse(labels(query + "span=1d ").contains("aligntime"));
    assertTrue(labels(query + "span=1h as ").isEmpty());
    assertEquals(List.of("|"), labels(query + "span=1h as hour"));
  }

  @Test
  public void numericBinOffersNumericStepsAndAvoidsOverriddenOrDuplicateOptions() {
    String query = "source=orders | bin amount ";
    assertTrue(labels(query).containsAll(List.of("span", "bins", "minspan", "start", "end")));
    assertFalse(labels(query).contains("aligntime"));
    assertEquals(List.of("1", "10", "100", "log10"), labels(query + "span="));
    assertEquals(List.of("as", "|"), labels(query + "span=10 "));
    assertEquals(List.of("10", "100"), labels(query + "bins="));
    assertEquals(List.of("start", "end", "as", "|"), labels(query + "bins=10 "));
    assertEquals(List.of("end", "as", "|"), labels(query + "bins=10 start=0 "));
    assertFalse(labels(query + "minspan=1 ").contains("bins"));
    assertFalse(labels(query + "span=1h ").contains("span"));
  }

  @Test
  public void binOutputsPreserveOtherFieldsAndAddOrReplaceTheBucket() {
    CompletionResponse numeric = complete("source=orders | bin amount span=10 | fields ");
    assertEquals(List.of("order_id", "amount"), fieldLabels(numeric));
    assertEquals(
        "varchar",
        numeric.items().stream()
            .filter(item -> item.label().equals("amount"))
            .findFirst()
            .orElseThrow()
            .detail());
    CompletionResponse named = complete("source=orders | bin amount span=10 as bucket | fields ");
    assertTrue(fieldLabels(named).containsAll(List.of("order_id", "amount", "bucket")));
    assertFalse(named.incomplete());
    CompletionResponse time =
        complete("source=`logevent-*` | bin timestamp span=1h as hour | fields ");
    assertEquals(
        "timestamp",
        time.items().stream()
            .filter(item -> item.label().equals("hour"))
            .findFirst()
            .orElseThrow()
            .detail());
    CompletionResponse month =
        complete("source=`logevent-*` | bin timestamp span=1mon as month | fields ");
    assertEquals(
        "varchar",
        month.items().stream()
            .filter(item -> item.label().equals("month"))
            .findFirst()
            .orElseThrow()
            .detail());
    assertTrue(fieldLabels(time).contains("timestamp"));
  }

  @Test
  public void binOnNestedFieldDoesNotKeepReferencesToDroppedStructParents() {
    CompletionResponse result =
        complete("source=`logevent-*` | bin http.status span=100 | fields ");
    assertFalse(fieldLabels(result).contains("http"));
    assertEquals(
        "`http.status`",
        result.items().stream()
            .filter(item -> item.label().equals("http.status"))
            .findFirst()
            .orElseThrow()
            .textEdit()
            .newText());
    assertTrue(fieldLabels(result).contains("message"));
  }

  @Test
  public void binParameterReplacementPreservesFollowingText() {
    String query = "source=`logevent-*` | bin timestamp spXXX=1h | fields timestamp";
    int caret = query.indexOf("spXXX") + 2;
    Item span =
        complete(query, caret).items().stream()
            .filter(item -> item.label().equals("span"))
            .findFirst()
            .orElseThrow();
    assertEquals(
        "source=`logevent-*` | bin timestamp span =1h | fields timestamp", apply(query, span));
    assertTrue(
        complete(apply(query, span), span.textEdit().from() + span.textEdit().newText().length())
            .items()
            .isEmpty());
  }

  @Test
  public void sharedSchemaSemanticsPreserveNamesAcrossAdditionalCommands() {
    assertTrue(
        fieldLabels("source=orders | convert num(amount) as parsed | where ")
            .containsAll(List.of("order_id", "amount", "parsed")));
    assertTrue(
        fieldLabels("source=orders | fieldformat formatted=amount | where ")
            .containsAll(List.of("amount", "formatted")));
    assertTrue(fieldLabels("source=orders | mvexpand amount | fields ").contains("amount"));
    CompletionResponse extracted =
        complete("source=orders | parse order_id '(?<part>.*)' | fields ");
    assertTrue(extracted.incomplete());
    assertTrue(fieldLabels(extracted).containsAll(List.of("amount", "order_id")));
  }

  private CompletionResponse complete(String text) {
    return complete(text, text.length());
  }

  private CompletionResponse complete(String text, int caret) {
    return provider.complete(
        new CompletionRequest("ppl", "logs", text, caret, 42, "input"), metadata);
  }

  private List<String> labels(String text) {
    return complete(text).items().stream().map(Item::label).toList();
  }

  private List<String> fieldLabels(String text) {
    return fieldLabels(complete(text));
  }

  private List<String> fieldLabels(CompletionResponse response) {
    return response.items().stream()
        .filter(item -> item.kind().equals("field"))
        .map(Item::label)
        .toList();
  }

  private String accept(String text, String label) {
    return apply(
        text,
        complete(text).items().stream()
            .filter(item -> item.label().equals(label))
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(label + " not found after " + text + ": " + labels(text))));
  }

  private static String apply(String text, Item item) {
    return text.substring(0, item.textEdit().from())
        + item.textEdit().newText()
        + text.substring(item.textEdit().to());
  }
}
