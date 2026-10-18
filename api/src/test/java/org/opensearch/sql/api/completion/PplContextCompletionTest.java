/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api.completion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;
import org.opensearch.sql.api.completion.CompletionMetadata.Field;
import org.opensearch.sql.api.completion.CompletionMetadata.Relation;
import org.opensearch.sql.api.completion.CompletionMetadata.RelationRef;
import org.opensearch.sql.api.completion.CompletionResponse.Item;

/**
 * Context tests deliberately use unrelated table schemas so a stale/wrong binding is observable.
 */
public class PplContextCompletionTest {
  private static final Set<String> ORDER_FIELDS = Set.of("order_id", "customer_id", "amount");
  private static final Set<String> CUSTOMER_FIELDS =
      Set.of("customer_key", "customer_name", "risk");
  private static final Set<String> DEVICE_FIELDS =
      Set.of("device_key", "device_name", "device.name");
  private final PplCompletionProvider provider = new PplCompletionProvider();
  private final CompletionMetadata metadata =
      new CompletionMetadata() {
        @Override
        public String resolveSource(String source) {
          return source;
        }

        @Override
        public List<Relation> tables(
            String source, List<String> namespace, String prefix, int limit) {
          return List.of("orders", "customers", "devices").stream()
              .filter(name -> name.startsWith(prefix))
              .map(name -> new Relation(namespace, name, "table"))
              .toList();
        }

        @Override
        public List<Field> fields(String source, RelationRef relation) {
          return switch (relation.name()) {
            case "orders" ->
                List.of(
                    new Field("order_id", "string"),
                    new Field("customer_id", "string"),
                    new Field("amount", "double"));
            case "customers" ->
                List.of(
                    new Field("customer_key", "string"),
                    new Field("customer_name", "string"),
                    new Field("risk", "double"));
            case "devices" ->
                List.of(
                    new Field("device_key", "string"),
                    new Field("device_name", "string"),
                    new Field("device.name", "string", List.of("device", "name")));
            default -> throw new AssertionError("Unexpected relation " + relation);
          };
        }
      };

  @Test
  public void lookupMappingAndOutputBindToTheirDifferentFieldOwners() {
    String prefix = "source=orders | lookup customers ";
    assertEquals(CUSTOMER_FIELDS, fields(prefix));
    assertEquals(ORDER_FIELDS, fields(prefix + "customer_key as "));
    assertEquals(CUSTOMER_FIELDS, fields(prefix + "customer_key as customer_id output "));
    assertTrue(fields(prefix + "customer_key as customer_id output customer_name as ").isEmpty());
  }

  @Test
  public void lookupFieldAcceptanceContinuesToMappingAndOutputKeywords() {
    String text = accept("source=orders | lookup ", "customers");
    text = accept(text, "customer_key");
    assertTrue(text + " must move past the accepted lookup field", labels(text).contains("as"));
    text = accept(text, "as");
    text = accept(text, "customer_id");
    assertTrue(text + " must finish the mapping", labels(text).contains("output"));
    text = accept(text, "output");
    text = accept(text, "customer_name");
    assertTrue(text + " must allow an output alias", labels(text).contains("as"));
  }

  @Test
  public void nestedSearchUsesItsOwnTableAndPropagatesItsOwnProjection() {
    assertEquals(CUSTOMER_FIELDS, fields("source=orders | append [source=customers | where "));
    assertEquals(
        Set.of("customer_name"),
        fields("source=orders | append [source=customers | fields customer_name | where "));
    assertEquals(
        Set.of("customer_name", "total"),
        fields(
            "source=orders | append [source=customers | stats count() as total by customer_name |"
                + " where "));
  }

  @Test
  public void nestedLookupUsesTheLookupTableRatherThanTheSubquerySource() {
    String prefix = "source=orders | append [source=customers | lookup devices ";
    assertEquals(DEVICE_FIELDS, fields(prefix));
    assertEquals(CUSTOMER_FIELDS, fields(prefix + "device_key as "));
  }

  @Test
  public void inheritedSubPipelinePropagatesItsOwnStages() {
    assertEquals(Set.of("amount"), fields("source=orders | appendpipe [ fields amount | where "));
    assertEquals(
        Set.of("total"), fields("source=orders | appendpipe [ stats count() as total | where "));
    assertEquals(Set.of("amount"), fields("source=orders | appendcol [ fields amount | where "));
  }

  @Test
  public void convertUsesItsRuntimeFunctionCatalogAndSeparatesInputFromAlias() {
    assertTrue(labels("source=orders | convert ").containsAll(List.of("num", "ctime", "dur2sec")));
    assertEquals(ORDER_FIELDS, fields("source=orders | convert num("));
    assertTrue(fields("source=orders | convert num(amount) as ").isEmpty());
    assertFalse(labels("source=orders | convert num(amount) as total ").contains("ctime"));
    assertTrue(labels("source=orders | convert num(amount) as total, ").contains("ctime"));
    assertTrue(labels("source=orders | convert nu").contains("num"));
  }

  @Test
  public void foreachUsesRuntimeOptionsAndModesAndCanAcceptThemContinuously() {
    String query = "source=orders | foreach ";
    assertTrue(labels(query).containsAll(List.of("mode", "fieldstr", "matchstr", "matchseg1")));
    query = accept(query, "mode");
    query = accept(query, "=");
    assertEquals(
        Set.of("multifield", "multivalue", "json_array", "auto_collections"), labels(query));
    query = accept(query, "multifield");
    assertTrue(fields(query).contains("amount"));
    assertFalse("The assigned mode parameter should not repeat", labels(query).contains("mode"));
    assertTrue(labels(query).contains("fieldstr"));
    assertFalse(labels(query).contains("multifield"));
  }

  @Test
  public void patternCommandsWaitForTheirRequiredStringBeforeOfferingTheNextPipe() {
    for (String command : List.of("parse", "grok")) {
      String query = "source=orders | " + command + " customer_id ";
      CompletionResponse response = complete(query);
      assertFalse(command + " still needs its pattern", labels(query).contains("|"));
      assertTrue(
          command + " should explain the required pattern", response.message().contains("字符串"));
      assertTrue(complete(query + "'unfinished").items().isEmpty());
      assertTrue(labels(query + "'(?<part>.*)' ").contains("|"));
    }
  }

  @Test
  public void joinConditionResolvesTheTableToTheRightOfTheCaret() {
    String query = "source=orders | join left=l right=r on l.customer_id=r.cu customers";
    int caret = query.indexOf("r.cu") + "r.cu".length();
    CompletionResponse response = complete(query, caret);
    assertEquals(Set.of("r.customer_key", "r.customer_name"), fields(response));
    Item selected =
        response.items().stream()
            .filter(item -> item.label().equals("r.customer_key"))
            .findFirst()
            .orElseThrow();
    assertTrue(apply(query, selected).endsWith(" customers"));

    String leftQuery = "source=orders | join left=l right=r on l.cu=r.customer_key customers";
    int leftCaret = leftQuery.indexOf("l.cu") + "l.cu".length();
    assertEquals(Set.of("l.customer_id"), fields(complete(leftQuery, leftCaret)));
  }

  @Test
  public void aFieldInsideAFunctionCanCloseItsCallBeforeContinuingThePredicate() {
    String query = "source=orders | where round(amount";
    assertTrue(
        "The function argument is complete, not a standalone comparison",
        labels(query).contains(")"));
    query = accept(query, ")");
    assertTrue(labels(query).contains(">"));
    assertTrue(labels(query + " > 1 ").contains("|"));
  }

  @Test
  public void graphLookupEdgeTokenCompletesBothFieldsAndTheDirectionContinuously() {
    String query = "source=orders | graphlookup devices start=customer_id edge=";
    Set<String> simpleFields =
        DEVICE_FIELDS.stream().filter(name -> !name.contains(".")).collect(Collectors.toSet());
    assertEquals(simpleFields, fields(query));
    assertFalse(fields(query).contains("device.name"));
    query = accept(query, "device_key");
    assertTrue(labels(query).containsAll(List.of("-->", "<->")));
    query = accept(query, "-->");
    assertEquals(simpleFields, fields(query));
    query = accept(query, "device_name");
    assertTrue(labels(query).containsAll(List.of("as", "maxdepth")));
    assertTrue(fields(accept(query, "as")).isEmpty());
    assertTrue(fields(query + "depthfield=").isEmpty());
  }

  private CompletionResponse complete(String text) {
    return complete(text, text.length());
  }

  private CompletionResponse complete(String text, int caret) {
    return provider.complete(
        new CompletionRequest("ppl", "logs", text, caret, 1, "input"), metadata);
  }

  private Set<String> labels(String text) {
    return complete(text).items().stream().map(Item::label).collect(Collectors.toSet());
  }

  private Set<String> fields(String text) {
    return fields(complete(text));
  }

  private Set<String> fields(CompletionResponse response) {
    return response.items().stream()
        .filter(item -> item.kind().equals("field"))
        .map(Item::label)
        .collect(Collectors.toSet());
  }

  private String accept(String text, String label) {
    Item item =
        complete(text).items().stream()
            .filter(candidate -> candidate.label().equals(label))
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        "Missing " + label + " after [" + text + "]: " + labels(text)));
    return apply(text, item);
  }

  private String apply(String text, Item item) {
    return text.substring(0, item.textEdit().from())
        + item.textEdit().newText()
        + text.substring(item.textEdit().to());
  }
}
