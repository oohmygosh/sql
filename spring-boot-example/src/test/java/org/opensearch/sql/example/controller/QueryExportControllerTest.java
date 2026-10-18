/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.opensearch.sql.data.model.ExprValueUtils.tupleValue;
import static org.opensearch.sql.data.type.ExprCoreType.INTEGER;
import static org.opensearch.sql.data.type.ExprCoreType.STRING;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
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
import org.opensearch.sql.data.model.ExprValue;
import org.opensearch.sql.example.QueryApplication;
import org.opensearch.sql.executor.ExecutionEngine.QueryResponse;
import org.opensearch.sql.executor.ExecutionEngine.Schema;
import org.opensearch.sql.executor.ExecutionEngine.Schema.Column;
import org.opensearch.sql.executor.QueryType;
import org.opensearch.sql.executor.pagination.Cursor;
import org.opensearch.sql.spring.ConfiguredQueryBackends;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(
    classes = QueryApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
@AutoConfigureMockMvc
// The connection owner is eager even when its service bean is overridden after configuration.
@MockitoBean(types = ConfiguredQueryBackends.class)
class QueryExportControllerTest {
  private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);
  private static final Schema EMPTY_RESULT_SCHEMA =
      new Schema(List.of(new Column("message", "消息", STRING), new Column("count", null, INTEGER)));

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @MockitoBean private UnifiedQueryService queries;

  @Test
  void exportsUtf8CsvWithEscapingAndSanitizationUsingDefaultSqlAndSource() throws Exception {
    String query = "SELECT message AS `消息` FROM logs";
    Schema schema =
        new Schema(
            List.of(
                new Column("message", "消息,名称", STRING),
                new Column("quote", "quoted", STRING),
                new Column("multiline", null, STRING),
                new Column("nullable", null, STRING),
                new Column("formula", "=formula", STRING),
                new Column("plus", null, STRING),
                new Column("minus", null, STRING),
                new Column("at", null, STRING)));
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("message", "中文,日志");
    row.put("quote", "say \"你好\"");
    row.put("multiline", "first\nsecond");
    row.put("nullable", null);
    row.put("formula", "=SUM(A1:A2)");
    row.put("plus", "+value");
    row.put("minus", "-value");
    row.put("at", "@value");
    when(queries.execute(null, QueryType.SQL, query)).thenReturn(response(schema, tupleValue(row)));

    String expected =
        "\"消息,名称\",quoted,multiline,nullable,'=formula,plus,minus,at"
            + System.lineSeparator()
            + "\"中文,日志\",\"say \"\"你好\"\"\",\"first\n"
            + "second\",,'=SUM(A1:A2),'+value,'-value,'@value";
    export(json.writeValueAsBytes(Map.of("query", query)))
        .andExpect(status().isOk())
        .andExpect(content().contentType(CSV))
        .andExpect(
            header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"result.csv\""))
        // Exact UTF-8 bytes also guard against adding a byte-order mark.
        .andExpect(content().bytes(expected.getBytes(StandardCharsets.UTF_8)));
    verify(queries).execute(null, QueryType.SQL, query);
  }

  @ParameterizedTest
  @MethodSource("routes")
  void exportsHeadersForEmptyResultsAndRoutesLanguageIgnoringCase(
      String source, String language, QueryType expectedLanguage) throws Exception {
    String query =
        expectedLanguage == QueryType.PPL
            ? "source=logs | fields message, count"
            : "SELECT message, count FROM logs";
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("source", source);
    request.put("language", language);
    request.put("query", query);
    when(queries.execute(source, expectedLanguage, query))
        .thenReturn(response(EMPTY_RESULT_SCHEMA));

    export(json.writeValueAsBytes(request))
        .andExpect(status().isOk())
        .andExpect(content().contentType(CSV))
        .andExpect(
            header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"result.csv\""))
        .andExpect(content().bytes("消息,count".getBytes(StandardCharsets.UTF_8)));
    verify(queries).execute(source, expectedLanguage, query);
  }

  private static Stream<Arguments> routes() {
    return Stream.of(
        Arguments.of(null, null, QueryType.SQL),
        Arguments.of("logs", "sQl", QueryType.SQL),
        Arguments.of("analytics", "pPl", QueryType.PPL));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"query\":null}",
        "{\"query\":\"\"}",
        "{\"query\":\" \\t\\r\\n\"}",
        "{\"query\":\"SELECT 1\",\"language\":\"\"}",
        "{\"query\":\"SELECT 1\",\"language\":\" \\t\"}",
        "{\"query\":\"SELECT 1\",\"language\":\"ESQL\"}",
        "{\"query\":\"SELECT 1\",\"source\":\"\"}",
        "{\"query\":\"SELECT 1\",\"source\":\" \\t\"}"
      })
  void rejectsInvalidFieldsBeforeExecutingQuery(String request) throws Exception {
    assertFailure(export(request.getBytes(StandardCharsets.UTF_8)), 400);
    verifyNoInteractions(queries);
  }

  @ParameterizedTest
  @ValueSource(strings = {"{", "[]", "null", ""})
  void rejectsInvalidJsonOrMissingRequestBody(String request) throws Exception {
    assertFailure(export(request.getBytes(StandardCharsets.UTF_8)), 400);
    verifyNoInteractions(queries);
  }

  @Test
  void returnsBadRequestForServiceArgumentErrorsWithoutCsvAttachment() throws Exception {
    String query = "SELECT 1";
    when(queries.execute("unknown", QueryType.SQL, query))
        .thenThrow(new IllegalArgumentException("Unknown data source"));

    assertFailure(export(json.writeValueAsBytes(Map.of("source", "unknown", "query", query))), 400);
  }

  @Test
  void returnsServerErrorForExecutionFailureWithoutCsvAttachment() throws Exception {
    String query = "SELECT 1";
    when(queries.execute(null, QueryType.SQL, query))
        .thenThrow(new IllegalStateException("Backend unavailable"));

    assertFailure(export(json.writeValueAsBytes(Map.of("query", query))), 500);
  }

  @Test
  void returnsServerErrorForErrorResponseInsteadOfExportingPartialResults() throws Exception {
    String query = "SELECT message, count FROM logs";
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("message", "partial result");
    row.put("count", 1);
    QueryResponse result = response(EMPTY_RESULT_SCHEMA, tupleValue(row));
    result.setError(new IllegalArgumentException("Query did not complete"));
    when(queries.execute(null, QueryType.SQL, query)).thenReturn(result);

    assertFailure(export(json.writeValueAsBytes(Map.of("query", query))), 500);
  }

  @Test
  void acceptsOnlyJsonPostRequests() throws Exception {
    assertFailure(mvc.perform(get("/query/export")), 405);
    assertFailure(
        mvc.perform(post("/query/export").contentType(MediaType.TEXT_PLAIN).content("SELECT 1")),
        415);
    verifyNoInteractions(queries);
  }

  private ResultActions export(byte[] request) throws Exception {
    return mvc.perform(
        post("/query/export").contentType(MediaType.APPLICATION_JSON).content(request));
  }

  private static void assertFailure(ResultActions result, int expectedStatus) throws Exception {
    result
        .andExpect(status().is(expectedStatus))
        .andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION))
        .andExpect(
            failure -> {
              String contentType = failure.getResponse().getContentType();
              if (contentType != null) {
                assertThat(MediaType.parseMediaType(contentType).isCompatibleWith(CSV)).isFalse();
              }
            });
  }

  private static QueryResponse response(Schema schema, ExprValue... rows) {
    return new QueryResponse(schema, List.of(rows), Cursor.None);
  }
}
