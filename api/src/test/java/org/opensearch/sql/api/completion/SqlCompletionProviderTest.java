/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api.completion;

import static org.junit.Assert.*;

import java.util.*;
import org.antlr.v4.runtime.*;
import org.junit.Test;
import org.opensearch.sql.api.completion.CompletionMetadata.*;
import org.opensearch.sql.api.completion.CompletionResponse.Item;
import org.opensearch.sql.common.antlr.CaseInsensitiveCharStream;
import org.opensearch.sql.sql.antlr.parser.*;

public class SqlCompletionProviderTest {
  final SqlCompletionProvider provider = new SqlCompletionProvider();
  final List<RelationRef> lookups = new ArrayList<>();
  final CompletionMetadata metadata =
      new CompletionMetadata() {
        public String resolveSource(String source) {
          return source;
        }

        public List<Relation> tables(
            String source, List<String> namespace, String prefix, int limit) {
          return List.of("logs", "orders", "third", "literal.dot", "tick`name").stream()
              .filter(n -> n.startsWith(prefix.toLowerCase(Locale.ROOT)))
              .map(n -> new Relation(namespace, n, "table"))
              .toList();
        }

        public List<Field> fields(String source, RelationRef ref) {
          lookups.add(ref);
          if (ref.name().equals("missing")) throw new MetadataException("missing table");
          if (ref.name().equals("third"))
            return List.of(new Field("id", "integer"), new Field("secret", "string"));
          return ref.name().equals("orders")
              ? List.of(new Field("id", "integer"), new Field("amount", "double"))
              : List.of(
                  new Field("id", "integer"),
                  new Field("message", "string"),
                  new Field("enabled", "boolean"),
                  new Field("http.status", "integer", List.of("http", "status")),
                  new Field("literal.dot", "string"));
        }
      };

  CompletionResponse complete(String query) {
    int marker = query.indexOf('¦');
    return provider.complete(
        new CompletionRequest(
            "sql",
            "logs",
            query.replace("¦", ""),
            marker < 0 ? query.length() : marker,
            17,
            "manual"),
        metadata);
  }

  List<String> labels(String query) {
    return complete(query).items().stream().map(Item::label).toList();
  }

  String accept(String query, String label) {
    var item =
        complete(query).items().stream()
            .filter(i -> i.label().equals(label))
            .findFirst()
            .orElseThrow(() -> new AssertionError(label + " missing: " + labels(query)));
    String raw = query.replace("¦", "");
    var edit = item.textEdit();
    return raw.substring(0, edit.from()) + edit.newText() + raw.substring(edit.to());
  }

  @Test
  public void grammarEntryAndTables() {
    assertTrue(labels("").containsAll(List.of("SELECT", "SHOW", "DESCRIBE")));
    assertEquals(List.of("SELECT"), labels("sel"));
    assertTrue(labels("SELECT * ").contains("FROM"));
    assertTrue(labels("SELECT * FROM ").containsAll(List.of("logs", "orders")));
    assertEquals(List.of("orders"), labels("SELECT * FROM or"));
    assertTrue(
        labels("SELECT * FROM logs ")
            .containsAll(List.of("WHERE", "GROUP", "ORDER", "LIMIT", "JOIN")));
    assertTrue(lookups.isEmpty());
    assertTrue(accept("SELECT * FROM literal", "literal.dot").contains("`literal.dot`"));
    assertTrue(accept("SELECT * FROM tick", "tick`name").contains("`tick``name`"));
  }

  @Test
  public void projectionUsesLaterFromAndPreservesSuffix() {
    assertTrue(labels("SELECT me¦ FROM logs").contains("message"));
    assertEquals("SELECT `message` FROM logs", accept("SELECT me¦ssage FROM logs", "message"));
    assertEquals(
        "SELECT `http`.`status` FROM logs", accept("SELECT http.st¦atus FROM logs", "http.status"));
    assertEquals("SELECT `literal.dot` FROM logs", accept("SELECT lit¦ FROM logs", "literal.dot"));
    assertTrue(labels("SELECT me¦ FROM orders").isEmpty());
    assertEquals(17, complete("SELECT * FROM logs WHERE ").documentVersion());
  }

  @Test
  public void aliasesAndJoinAmbiguity() {
    assertTrue(labels("SELECT l.¦ FROM logs AS l").containsAll(List.of("l.id", "l.message")));
    assertTrue(
        labels("SELECT * FROM logs l JOIN orders o ON ")
            .containsAll(List.of("l.id", "o.id", "message", "amount")));
    assertFalse(labels("SELECT * FROM logs l JOIN orders o ON ").contains("id"));
    assertEquals(
        "SELECT * FROM logs l JOIN orders o ON `l`.`id`",
        accept("SELECT * FROM logs l JOIN orders o ON l.i", "l.id"));
    assertTrue(labels("SELECT * FROM logs l LEFT JOIN orders o ON o.").contains("o.amount"));
    assertFalse(labels("SELECT * FROM logs l JOIN orders o ON o.").contains("o.message"));
  }

  @Test
  public void derivedTableAndUnionScopes() {
    assertTrue(labels("SELECT d.¦ FROM (SELECT message AS msg FROM logs) d").contains("d.msg"));
    assertFalse(
        labels("SELECT d.¦ FROM (SELECT message AS msg FROM logs) d").contains("d.message"));
    assertTrue(labels("SELECT * FROM logs WHERE id IN (SELECT ¦ FROM orders)").contains("amount"));
    assertFalse(
        labels("SELECT * FROM logs WHERE id IN (SELECT ¦ FROM orders)").contains("message"));
    assertTrue(labels("SELECT * FROM logs UNION SELECT ¦ FROM orders").contains("amount"));
    assertFalse(labels("SELECT * FROM logs UNION SELECT ¦ FROM orders").contains("message"));
  }

  @Test
  public void outputAliasesOnlyInAllowedClauses() {
    assertTrue(labels("SELECT message AS msg FROM logs ORDER BY ").contains("msg"));
    assertTrue(labels("SELECT message AS msg FROM logs GROUP BY ").contains("msg"));
    assertFalse(labels("SELECT message AS msg FROM logs WHERE ").contains("msg"));
    assertFalse(labels("SELECT message AS ¦ FROM logs").contains("message"));
  }

  @Test
  public void commentsStringsUnicodeAndMetadataFailure() {
    for (String q :
        List.of(
            "SELECT 'abc¦",
            "SELECT * FROM logs -- comment¦",
            "SELECT /* comment¦",
            "SELECT # comment¦",
            "SELECT --¦")) assertTrue(q, labels(q).isEmpty());
    assertEquals(
        "SELECT '😀', `message` FROM logs", accept("SELECT '😀', me¦ FROM logs", "message"));
    var failed = complete("SELECT * FROM `missing` WHERE ");
    assertTrue(failed.incomplete());
    assertTrue(failed.message(), failed.message().contains("missing table"));
    assertTrue(failed.items().stream().anyMatch(i -> i.label().equals("NOT")));
    assertTrue(complete("SELECT " + "(".repeat(65)).incomplete());
  }

  @Test
  public void aNewLineEndsEachSupportedLineComment() {
    assertTrue(labels("#\nsel").contains("SELECT"));
    assertTrue(labels("-- comment\nsel").contains("SELECT"));
    assertTrue(labels("--\nsel").contains("SELECT"));
  }

  @Test
  public void continuousQueryParsesWithExecutionGrammar() {
    String q = accept("", "SELECT");
    q = accept(q, "*");
    q = accept(q, "FROM");
    q = accept(q, "logs");
    q = accept(q, "WHERE");
    q = accept(q, "id");
    q = accept(q, "=") + "1 ";
    q = accept(q, "ORDER");
    q = accept(q, "BY");
    q = accept(q, "id");
    q = accept(q, "DESC");
    q = accept(q, "LIMIT") + "10";
    var parser =
        new OpenSearchSQLParser(
            new CommonTokenStream(new OpenSearchSQLLexer(new CaseInsensitiveCharStream(q))));
    parser.root();
    assertEquals(q, 0, parser.getNumberOfSyntaxErrors());
  }

  @Test
  public void sqlJoinPredicateDoesNotDegradeAnOtherwiseCompleteAnalysis() {
    for (String join : List.of("JOIN", "LEFT JOIN", "RIGHT JOIN", "INNER JOIN", "CROSS JOIN")) {
      String q = "SELECT * FROM logs l " + join + " orders o ON ";
      assertFalse(q, complete(q).incomplete());
      assertTrue(q, labels(q).contains("amount"));
    }
    assertFalse(complete("SELECT * FROM logs WHERE ").incomplete());
  }

  @Test
  public void namespaceAndQuotedTableReplacementKeepIdentifierComponents() {
    assertEquals(
        "SELECT * FROM `db`.`orders` WHERE id = 1",
        accept("SELECT * FROM db.or¦ders WHERE id = 1", "db.orders"));
    assertTrue(labels("SELECT * FROM orders").contains("WHERE"));
    labels("SELECT * FROM `db`.`orders` WHERE ");
    assertTrue(lookups.contains(new RelationRef(List.of("db"), "orders")));
  }

  @Test
  public void functionHeadsAreUniqueAndInternalKeywordsDoNotAddParentheses() {
    assertEquals(
        1, complete("SELECT co").items().stream().filter(i -> i.label().equals("COUNT")).count());
    assertTrue(
        complete("SELECT co").items().stream()
            .filter(i -> i.label().equals("COUNT"))
            .allMatch(i -> i.kind().equals("function") && i.textEdit().newText().equals("COUNT(")));
    assertEquals("SELECT CAST(id AS ", accept("SELECT CAST(id ", "AS"));
    assertEquals("SELECT EXTRACT(YEAR FROM ", accept("SELECT EXTRACT(YEAR ", "FROM"));
  }

  @Test
  public void dynamicTableFunctionsKeepGrammarAndReportUnknownFields() {
    var result = complete("SELECT * FROM read_logs('path') r WHERE ");
    assertTrue(result.incomplete());
    assertFalse(result.message().isEmpty());
    assertFalse(result.items().stream().anyMatch(i -> i.kind().equals("field")));
    assertTrue(result.items().stream().anyMatch(i -> i.label().equals("NOT")));
  }

  @Test
  public void incompleteProjectionAndExistingSuffixDoNotLoseTheirContext() {
    assertFalse(labels("SELECT * FROM logs ¦WHERE id = 1").contains("WHERE"));
    var failed = complete("SELECT me¦ FROM `missing`");
    assertTrue(failed.incomplete());
    assertTrue(failed.message(), failed.message().contains("missing table"));
  }

  @Test
  public void joinOnSeesOnlyRelationsAlreadyIntroducedButProjectionSeesAll() {
    String on = "SELECT * FROM logs l JOIN orders o ON ¦ JOIN third t ON t.id=o.id";
    assertTrue(labels(on).containsAll(List.of("l.id", "o.id", "amount")));
    assertFalse(labels(on).contains("t.id"));
    assertFalse(labels(on).contains("secret"));
    assertFalse(lookups.stream().anyMatch(ref -> ref.name().equals("third")));
    assertTrue(
        labels("SELECT t.se¦ FROM logs l JOIN orders o ON l.id=o.id JOIN third t ON t.id=o.id")
            .contains("t.secret"));
    assertTrue(
        labels("SELECT * FROM logs l JOIN orders o ON l.id=o.id JOIN third t ON t.")
            .contains("t.secret"));
  }

  @Test
  public void nestedClausesDoNotMakeOutputAliasesVisibleInOuterWhere() {
    for (String clause : List.of("ORDER BY id", "GROUP BY id", "GROUP BY id HAVING id > 0")) {
      String query =
          "SELECT message AS msg FROM logs WHERE id IN (SELECT id FROM orders "
              + clause
              + ") AND ¦";
      assertTrue(query, labels(query).contains("message"));
      assertFalse(query, labels(query).contains("msg"));
    }
    assertTrue(
        labels(
                "SELECT message AS msg FROM logs WHERE id IN (SELECT id FROM orders ORDER BY id)"
                    + " ORDER BY ms¦")
            .contains("msg"));
  }

  @Test
  public void derivedComputedColumnNamesKeepSourceWhitespaceAndUnicodeOffsets() {
    assertTrue(labels("SELECT d.¦ FROM (SELECT id + 1 FROM logs) d").contains("d.id + 1"));
    assertFalse(labels("SELECT d.¦ FROM (SELECT id + 1 FROM logs) d").contains("d.id+1"));
    assertEquals(
        "SELECT `d`.`COUNT( * )` FROM (SELECT COUNT( * ) FROM logs) d",
        accept("SELECT d.¦ FROM (SELECT COUNT( * ) FROM logs) d", "d.COUNT( * )"));
    assertTrue(labels("SELECT d.¦ FROM (SELECT '😀', id + 1 FROM logs) d").contains("d.id + 1"));
    assertTrue(labels("SELECT id + 1 FROM logs ORDER BY ¦").contains("id + 1"));
  }

  @Test
  public void correlatedSubqueriesInheritBindingsWithAliasShadowingAndDerivedBarriers() {
    assertTrue(
        labels("SELECT * FROM logs l WHERE EXISTS (SELECT * FROM orders o WHERE l.¦)")
            .containsAll(List.of("l.id", "l.message")));
    assertTrue(
        labels("SELECT * FROM logs l WHERE EXISTS (SELECT * FROM orders o WHERE ¦)")
            .containsAll(List.of("id", "amount", "l.id")));
    assertFalse(
        labels("SELECT * FROM logs l WHERE EXISTS (SELECT * FROM orders l WHERE l.¦)")
            .contains("l.message"));
    assertTrue(
        labels(
                "SELECT * FROM logs l WHERE EXISTS (SELECT * FROM orders o WHERE EXISTS (SELECT *"
                    + " FROM third t WHERE l.¦))")
            .contains("l.message"));
    assertFalse(
        labels("SELECT * FROM logs l JOIN (SELECT * FROM orders o WHERE l.¦) d ON l.id=d.id")
            .contains("l.message"));
    assertFalse(
        labels(
                "SELECT * FROM logs l JOIN orders o ON EXISTS (SELECT * FROM third t WHERE n.¦)"
                    + " JOIN logs n ON n.id=l.id")
            .contains("n.message"));
    assertFalse(
        labels(
                "SELECT * FROM logs l WHERE EXISTS (SELECT * FROM (SELECT * FROM orders o) d WHERE"
                    + " o.¦)")
            .contains("o.amount"));
  }

  @Test
  public void qualifiedNamesAllowWhitespaceWithoutChangingQuotedComponentsOrSuffix() {
    for (String name : List.of("l. me¦ssage", "l . me¦ssage", "l\n.\tme¦ssage")) {
      assertEquals(
          "SELECT `l`.`message` FROM logs l",
          accept("SELECT " + name + " FROM logs l", "l.message"));
    }
    assertEquals(
        "SELECT `l`.`message` FROM logs l", accept("SELECT l . ¦ FROM logs l", "l.message"));
    assertEquals(
        "SELECT `l`.`http`.`status` FROM logs l",
        accept("SELECT l . http . st¦atus FROM logs l", "l.http.status"));
    assertEquals(
        "SELECT * FROM `db`.`orders` WHERE id=1",
        accept("SELECT * FROM db . or¦ders WHERE id=1", "db.orders"));
    assertEquals(
        "SELECT ` l `.`message` FROM logs AS ` l `",
        accept("SELECT ` l ` . me¦ FROM logs AS ` l `", " l .message"));
  }

  @Test
  public void functionPrefixKeepsExistingParenthesis() {
    assertTrue(labels("SELECT su¦ FROM orders").contains("SUM"));
    assertEquals(
        "SELECT SUM(amount) FROM orders", accept("SELECT su¦m(amount) FROM orders", "SUM"));
    assertTrue(labels("SELECT SUM(¦) FROM orders").contains("amount"));
  }
}
