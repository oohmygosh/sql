/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.opensearch.sql.data.model.ExprValueUtils.*;
import static org.opensearch.sql.data.type.ExprCoreType.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.common.antlr.SyntaxCheckException;
import org.opensearch.sql.common.error.ErrorCode;
import org.opensearch.sql.common.error.ErrorReport;
import org.opensearch.sql.data.model.ExprDecimalValue;
import org.opensearch.sql.data.model.ExprTimestampValue;
import org.opensearch.sql.data.model.ExprValue;
import org.opensearch.sql.example.QueryApplication;
import org.opensearch.sql.exception.SemanticCheckException;
import org.opensearch.sql.executor.ExecutionEngine.QueryResponse;
import org.opensearch.sql.executor.ExecutionEngine.Schema;
import org.opensearch.sql.executor.ExecutionEngine.Schema.Column;
import org.opensearch.sql.executor.QueryType;
import org.opensearch.sql.executor.Warning;
import org.opensearch.sql.executor.pagination.Cursor;
import org.opensearch.sql.spring.ConfiguredQueryBackends;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    classes = QueryApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@MockitoBean(types = ConfiguredQueryBackends.class)
class QueryControllerTest {
  private static final Schema SCHEMA = new Schema(List.of(new Column("n", "数量", LONG)));
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @MockitoBean UnifiedQueryService queries;

  @ParameterizedTest
  @MethodSource("routes")
  void routesBothBackendsAndLanguagesAndPreservesSchemaForEmptyRows(
      String source, String language, QueryType expected) throws Exception {
    String query =
        expected == QueryType.SQL
            ? "SELECT count(*) AS n FROM events"
            : "source=events | stats count() as n";
    when(queries.execute(source, expected, query)).thenReturn(response(SCHEMA));
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("source", source);
    request.put("language", language);
    request.put("query", query);
    mvc.perform(
            post("/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(request)))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.schema[0].name").value("数量"))
        .andExpect(
            jsonPath("$.schema[0].type").value(expected == QueryType.SQL ? "long" : "bigint"))
        .andExpect(jsonPath("$.datarows").isEmpty())
        .andExpect(jsonPath("$.size").value(0))
        .andExpect(jsonPath("$.total").value(0))
        .andExpect(jsonPath("$.warnings").doesNotExist())
        .andExpect(header().doesNotExist("Content-Disposition"));
    verify(queries).execute(source, expected, query);
  }

  private static Stream<Arguments> routes() {
    return Stream.of(
        Arguments.of(null, null, QueryType.SQL),
        Arguments.of("logs", "sQl", QueryType.SQL),
        Arguments.of("logs", "pPl", QueryType.PPL),
        Arguments.of("analytics", "SQL", QueryType.SQL),
        Arguments.of("analytics", "PPL", QueryType.PPL));
  }

  @Test
  void jsonPreservesSchemaOrderAliasesNullsNestedValuesAndExactNumbers() throws Exception {
    String query = "SELECT message, nullable, detail, amount, timestamp FROM events";
    var schema =
        new Schema(
            List.of(
                new Column("message", "消息", STRING),
                new Column("nullable", null, STRING),
                new Column("detail", null, STRUCT),
                new Column("amount", null, DOUBLE),
                new Column("timestamp", null, TIMESTAMP)));
    // Intentionally reverse the tuple's order: datarows must still match the schema's order.
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("timestamp", new ExprTimestampValue("2026-10-18 12:34:56.123456789"));
    BigDecimal exact = new BigDecimal("18446744073709551615.123456789");
    values.put("amount", new ExprDecimalValue(exact));
    values.put("detail", Map.of("tags", List.of("中文", "value"), "count", 9007199254740993L));
    values.put("nullable", null);
    values.put("message", "=日志,\"内容\"\n下一行");
    QueryResponse response = response(schema, tupleValue(values));
    response.setWarnings(
        List.of(new Warning(Warning.TYPE_PARTIAL_RESULT, "Result warning", "Details")));
    when(queries.execute(null, QueryType.SQL, query)).thenReturn(response);
    var result =
        mvc.perform(
                post("/query")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(Map.of("query", query))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.size").value(1))
            .andExpect(jsonPath("$.warnings[0].type").value("PARTIAL_RESULT"))
            .andReturn()
            .getResponse();
    var body =
        json.copy()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .readTree(result.getContentAsByteArray());
    var row = body.get("datarows").get(0);
    assertThat(body.get("schema").get(0).get("name").asText()).isEqualTo("消息");
    assertThat(row.get(0).asText()).isEqualTo(values.get("message"));
    assertThat(row.get(1).isNull()).isTrue();
    assertThat(row.get(2).get("tags").get(0).asText()).isEqualTo("中文");
    assertThat(row.get(2).get("count").asLong()).isEqualTo(9007199254740993L);
    assertThat(row.get(3).decimalValue()).isEqualByComparingTo(exact);
    assertThat(row.get(4).asText()).isEqualTo("2026-10-18 12:34:56.123456789");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "null",
        "[]",
        "{",
        "{}",
        "{\"query\":null}",
        "{\"query\":\" \\t\"}",
        "{\"query\":\"SELECT 1\",\"source\":\" \"}",
        "{\"query\":\"SELECT 1\",\"language\":\" \"}",
        "{\"query\":\"SELECT 1\",\"language\":\"ESQL\"}"
      })
  void rejectsInvalidRequestBeforeQuerying(String request) throws Exception {
    mvc.perform(post("/query").contentType(MediaType.APPLICATION_JSON).content(request))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(queries);
  }

  @ParameterizedTest
  @MethodSource("failures")
  void mapsQueryFailuresToHttpStatus(RuntimeException failure, int code) throws Exception {
    when(queries.execute(null, QueryType.SQL, "SELECT 1")).thenThrow(failure);
    mvc.perform(
            post("/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"SELECT 1\"}"))
        .andExpect(status().is(code))
        .andExpect(jsonPath("$.datarows").doesNotExist());
  }

  private static Stream<Arguments> failures() {
    return Stream.of(
        Arguments.of(new IllegalArgumentException("Unknown data source"), 400),
        Arguments.of(new SyntaxCheckException("Invalid SQL"), 400),
        Arguments.of(new SemanticCheckException("Unknown field"), 400),
        Arguments.of(new UnsupportedOperationException("Unsupported feature"), 400),
        Arguments.of(new IllegalStateException("Backend unavailable"), 500),
        Arguments.of(report(ErrorCode.FIELD_NOT_FOUND), 400),
        Arguments.of(report(ErrorCode.PERMISSION_DENIED), 403),
        Arguments.of(report(ErrorCode.RESOURCE_LIMIT_EXCEEDED), 429),
        Arguments.of(report(ErrorCode.EXECUTION_ERROR), 500));
  }

  private static ErrorReport report(ErrorCode code) {
    return ErrorReport.wrap(new IllegalStateException("Engine error")).code(code).build();
  }

  @Test
  void doesNotReturnPartialRowsFromAnErrorResponse() throws Exception {
    QueryResponse result = response(SCHEMA, tupleValue(Map.of("n", 1L)));
    result.setError(new IllegalArgumentException("Query incomplete"));
    when(queries.execute(null, QueryType.SQL, "SELECT 1")).thenReturn(result);
    mvc.perform(
            post("/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"SELECT 1\"}"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.datarows").doesNotExist());
  }

  @Test
  void acceptsOnlyJsonPostRequests() throws Exception {
    mvc.perform(get("/query")).andExpect(status().isMethodNotAllowed());
    mvc.perform(post("/query").contentType(MediaType.TEXT_PLAIN).content("SELECT 1"))
        .andExpect(status().isUnsupportedMediaType());
    verifyNoInteractions(queries);
  }

  private static QueryResponse response(Schema schema, ExprValue... rows) {
    return new QueryResponse(schema, List.of(rows), Cursor.None);
  }
}
