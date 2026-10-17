/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api.corpus;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.backend.QueryBackend;
import org.opensearch.sql.executor.CalciteExecutionEngine;
import org.opensearch.sql.executor.ExecutionEngine;
import org.opensearch.sql.executor.QueryType;

/**
 * Shared, data-backed differential corpus. Each query also runs in the independent scan backend.
 */
public final class BackendQueryCorpus {
  private static final Gson JSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
  public static final List<String> COLUMNS =
      List.of(
          "id",
          "team",
          "username",
          "status",
          "amount",
          "bytes",
          "message",
          "tags",
          "ts",
          "payload");
  public static final List<String> DEPARTMENT_COLUMNS = List.of("team", "owner", "budget");
  private static final String[] TEAMS = {
    "alpha", "beta", "alpha", "gamma", "beta", "alpha", null, "gamma", "alpha", "beta", "alpha",
    null
  };
  private static final String[] USERS = {
    "alice", "bob", "carol", "dave", "alice", "eve", "bob", "frank", "alice", "eve", "Grace", "汉字"
  };
  private static final Integer[] STATUS = {
    200, 500, 200, 404, 500, 200, 404, 200, 500, 200, 404, 200
  };
  private static final Double[] AMOUNTS = {
    10.5, null, 20.0, 0.0, null, -2.5, 30.0, 40.75, 10.5, 5.0, 15.25, 1.5
  };
  private static final List<List<String>> TAGS =
      List.of(
          List.of("red", "hot"),
          List.of("blue"),
          List.of("red"),
          List.of(),
          List.of("blue", "cold"),
          List.of("red"),
          List.of("cold"),
          List.of("hot"),
          List.of("red", "hot"),
          List.of("blue"),
          List.of(),
          List.of("green"));

  public static final class QueryCase {
    public String id;
    public QueryType language;
    public String family;
    public String query;
    public boolean ordered;
    public List<Map<String, Object>> expected;

    @Override
    public String toString() {
      return id;
    }
  }

  public static List<QueryCase> cases() {
    try (var input = BackendQueryCorpus.class.getResourceAsStream("/corpus/backend-queries.json")) {
      if (input == null) throw new IllegalStateException("Missing query corpus");
      return Arrays.asList(
          JSON.fromJson(new InputStreamReader(input, StandardCharsets.UTF_8), QueryCase[].class));
    } catch (Exception e) {
      throw new IllegalStateException("Cannot read query corpus", e);
    }
  }

  public static List<Object[]> eventRows() {
    List<Object[]> rows = new ArrayList<>();
    long start = Instant.parse("2025-01-01T00:00:00Z").toEpochMilli();
    for (int i = 0; i < 12; i++) {
      rows.add(
          new Object[] {
            i + 1,
            TEAMS[i],
            USERS[i],
            STATUS[i],
            AMOUNTS[i],
            (long) (i + 1) * 100,
            USERS[i] + (STATUS[i] >= 400 ? " error" : " login") + " code=" + STATUS[i],
            new ArrayList<>(TAGS.get(i)),
            start + i * 3600000L,
            "{\"region\":\"" + (i % 2 == 0 ? "east" : "west") + "\",\"priority\":" + (i % 3) + "}"
          });
    }
    return rows;
  }

  public static List<Object[]> departmentRows() {
    return List.of(
        new Object[] {"alpha", "Ana", 100},
        new Object[] {"beta", "Ben", 200},
        new Object[] {"delta", "Dan", 400},
        new Object[] {null, "Nobody", 0});
  }

  public static List<Map<String, Object>> documents(boolean events, boolean clickHouse) {
    List<String> columns = events ? COLUMNS : DEPARTMENT_COLUMNS;
    List<Map<String, Object>> result = new ArrayList<>();
    for (Object[] row : events ? eventRows() : departmentRows()) {
      Map<String, Object> document = new LinkedHashMap<>();
      for (int i = 0; i < row.length; i++) {
        Object value = row[i];
        if (columns.get(i).equals("ts")) {
          value = Instant.ofEpochMilli((Long) value).toString();
          if (clickHouse) value = value.toString().replace('T', ' ').replace("Z", "");
        }
        document.put(columns.get(i), value);
      }
      // Cover both an absent ES value and an explicit null using the same SQL NULL oracle.
      if (events && !clickHouse && document.get("id").equals(2)) document.remove("amount");
      result.add(document);
    }
    return result;
  }

  public static String json(Object value) {
    return JSON.toJson(value);
  }

  public static String jsonLines(boolean events, boolean clickHouse) {
    Gson compact = new GsonBuilder().serializeNulls().create();
    return String.join("\n", documents(events, clickHouse).stream().map(compact::toJson).toList())
        + "\n";
  }

  private static QueryBackend referenceBackend() {
    return new QueryBackend() {
      @Override
      public String name() {
        return "reference";
      }

      @Override
      public String type() {
        return "memory-corpus";
      }

      @Override
      public Schema createSchema(SchemaPlus parent) {
        return new AbstractSchema() {
          @Override
          protected Map<String, org.apache.calcite.schema.Table> getTableMap() {
            return Map.of("events", new MemoryTable(true), "departments", new MemoryTable(false));
          }
        };
      }

      @Override
      public ExecutionEngine executionEngine() {
        return new CalciteExecutionEngine();
      }
    };
  }

  private static final class MemoryTable extends AbstractTable implements ScannableTable {
    private final boolean events;

    private MemoryTable(boolean events) {
      this.events = events;
    }

    @Override
    public RelDataType getRowType(RelDataTypeFactory factory) {
      var builder = factory.builder();
      for (String name : events ? COLUMNS : DEPARTMENT_COLUMNS) {
        SqlTypeName type =
            switch (name) {
              case "id", "status", "budget" -> SqlTypeName.INTEGER;
              case "amount" -> SqlTypeName.DOUBLE;
              case "bytes" -> SqlTypeName.BIGINT;
              case "ts" -> SqlTypeName.TIMESTAMP;
              case "tags" -> SqlTypeName.ARRAY;
              default -> SqlTypeName.VARCHAR;
            };
        RelDataType dataType =
            type == SqlTypeName.ARRAY
                ? factory.createArrayType(factory.createSqlType(SqlTypeName.VARCHAR), -1)
                : factory.createSqlType(type);
        builder.add(name, factory.createTypeWithNullability(dataType, true));
      }
      return builder.build();
    }

    @Override
    public Enumerable<Object[]> scan(DataContext root) {
      return Linq4j.asEnumerable(events ? eventRows() : departmentRows());
    }
  }

  public static UnifiedQueryService referenceService() {
    return new UnifiedQueryService(List.of(referenceBackend()), "reference");
  }

  private final UnifiedQueryService reference = referenceService();
  private final List<Map<String, Object>> observations = new ArrayList<>();

  public void verify(
      QueryCase query, UnifiedQueryService remote, String events, String departments) {
    Map<String, Object> observation = new LinkedHashMap<>();
    observation.put("id", query.id);
    observation.put("language", query.language);
    observation.put("family", query.family);
    observation.put("query", query.query);
    observation.put("stage", "reference");
    long started = System.nanoTime();
    try {
      var expected =
          normalize(
              reference.execute(query.language, render(query, "events", "departments")),
              query.ordered);
      if (query.expected != null) {
        var golden = normalizedRows(query.expected, query.ordered);
        if (!golden.equals(expected))
          throw new AssertionError(
              "Reference differs from golden rows: expected=" + golden + ", actual=" + expected);
      }
      observation.put("stage", "remote");
      List<String> actual;
      try (var hook =
          org.apache.calcite.runtime.Hook.QUERY_PLAN.addThread(
              (Object nativePlan) ->
                  ((List<String>)
                          observation.computeIfAbsent(
                              "nativePlans", key -> new ArrayList<String>()))
                      .add(nativePlan.toString()))) {
        actual =
            normalize(
                remote.execute(query.language, render(query, events, departments)), query.ordered);
      }
      if (!expected.equals(actual))
        throw new AssertionError("Rows differ: expected=" + expected + ", actual=" + actual);
      observation.put("status", "passed");
      observation.put("rowCount", actual.size());
    } catch (Throwable error) {
      observation.put("status", "failed");
      observation.put("error", error.toString());
      Throwable root = error;
      while (root.getCause() != null && root.getCause() != root) root = root.getCause();
      observation.put("rootCause", root.toString());
      throw new AssertionError(
          query.id + " [" + observation.get("stage") + "] " + query.query, error);
    } finally {
      observation.put("milliseconds", (System.nanoTime() - started) / 1000000L);
      observations.add(observation);
    }
  }

  private static String render(QueryCase query, String events, String departments) {
    return query
        .query
        .replace("${events}", "`" + events + "`")
        .replace("${departments}", "`" + departments + "`");
  }

  private static List<String> normalize(ExecutionEngine.QueryResponse result, boolean ordered) {
    List<Map<String, Object>> rows =
        result.getResults().stream().map(row -> (Map<String, Object>) row.value()).toList();
    return normalizedRows(rows, ordered);
  }

  private static List<String> normalizedRows(List<Map<String, Object>> rows, boolean ordered) {
    List<String> values =
        new ArrayList<>(rows.stream().map(row -> JSON.toJson(canonical(row))).toList());
    if (!ordered) values.sort(Comparator.naturalOrder());
    return values;
  }

  private static Object canonical(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> sorted = new TreeMap<>();
      map.forEach((key, v) -> sorted.put(key.toString(), canonical(v)));
      return sorted;
    }
    if (value instanceof List<?> list)
      return list.stream().map(BackendQueryCorpus::canonical).toList();
    if (value instanceof Number number) {
      BigDecimal decimal = new BigDecimal(number.toString());
      if (number instanceof Double || number instanceof Float)
        decimal = decimal.round(new MathContext(12));
      return "number:" + decimal.stripTrailingZeros().toPlainString();
    }
    return value;
  }

  public void writeReport(Path path) throws Exception {
    Files.createDirectories(path.getParent());
    Files.writeString(path, JSON.toJson(observations) + "\n");
  }
}
