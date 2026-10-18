/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.data.model.ExprValueUtils;
import org.opensearch.sql.data.type.ExprCoreType;
import org.opensearch.sql.example.QueryApplication;
import org.opensearch.sql.executor.ExecutionEngine.Schema;
import org.opensearch.sql.executor.QueryRowConsumer;
import org.opensearch.sql.executor.QueryType;
import org.opensearch.sql.job.QueryJobId;
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
class QueryExportTaskControllerTest {
  private static final String ROOT = "/query/export/tasks";
  private static final Schema SCHEMA =
      new Schema(List.of(new Schema.Column("message", "消息", ExprCoreType.STRING)));
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @MockitoBean UnifiedQueryService queries;

  @Test
  void retainsFastTaskAndDownloadsExactCsvBytes() throws Exception {
    when(queries.stream(eq("analytics"), eq(QueryType.PPL), eq("source=events"), any(), any()))
        .thenAnswer(
            invocation -> {
              QueryRowConsumer sink = invocation.getArgument(3);
              sink.schema(SCHEMA);
              sink.row(ExprValueUtils.tupleValue(Map.of("message", "中文,\"日志\"\n第二行")));
              sink.row(ExprValueUtils.tupleValue(Map.of("message", "=SUM(A1:A2)")));
              return List.of();
            });
    var response =
        mvc.perform(
                post(ROOT)
                    .principal(() -> "alice")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"source\":\"analytics\",\"language\":\"pPl\",\"query\":\"source=events\"}"))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse();
    String id = json.readTree(response.getContentAsByteArray()).get("id").asText();
    assertThat(response.getHeader("Location")).isEqualTo(ROOT + "/" + id);
    JsonNode state = terminal(id, "alice");
    assertThat(state.get("state").asText()).isEqualTo("SUCCEEDED");
    assertThat(state.get("exportedRows").asLong()).isEqualTo(2);
    String expected =
        "消息"
            + System.lineSeparator()
            + "\"中文,\"\"日志\"\"\n第二行\""
            + System.lineSeparator()
            + "'=SUM(A1:A2)";
    byte[] bytes = expected.getBytes(StandardCharsets.UTF_8);
    assertThat(state.get("fileSizeBytes").asLong()).isEqualTo(bytes.length);
    mvc.perform(get(ROOT + "/" + id + "/file").principal(() -> "alice"))
        .andExpect(status().isOk())
        .andExpect(content().bytes(bytes))
        .andExpect(header().string("Content-Length", Integer.toString(bytes.length)))
        .andExpect(header().string("Content-Disposition", "attachment; filename=\"result.csv\""));
    mvc.perform(get(ROOT + "/" + id).principal(() -> "bob")).andExpect(status().isForbidden());
    mvc.perform(get(ROOT + "/" + id + "/file")).andExpect(status().isForbidden());
  }

  @Test
  void exposesProgressWhileRunningAndDisallowsPartialDownloads() throws Exception {
    CountDownLatch writing = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);
    when(queries.stream(
            isNull(), eq(QueryType.SQL), eq("SELECT message FROM events"), any(), any()))
        .thenAnswer(
            invocation -> {
              QueryRowConsumer sink = invocation.getArgument(3);
              sink.schema(SCHEMA);
              for (int i = 0; i < 1000; i++)
                sink.row(ExprValueUtils.tupleValue(Map.of("message", "row")));
              writing.countDown();
              assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue();
              return List.of();
            });
    String id = submit("SELECT message FROM events");
    try {
      assertThat(writing.await(5, TimeUnit.SECONDS)).isTrue();
      mvc.perform(get(ROOT + "/" + id))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.state").value("RUNNING"))
          .andExpect(jsonPath("$.exportedRows").value(1000));
      mvc.perform(get(ROOT + "/" + id + "/file"))
          .andExpect(status().isConflict())
          .andExpect(header().doesNotExist("Content-Disposition"));
    } finally {
      finish.countDown();
    }
    assertThat(terminal(id, null).get("state").asText()).isEqualTo("SUCCEEDED");
  }

  @Test
  void failuresRemainPollableAndCannotBeDownloaded() throws Exception {
    when(queries.stream(isNull(), eq(QueryType.SQL), eq("SELECT broken"), any(), any()))
        .thenAnswer(
            invocation -> {
              QueryRowConsumer sink = invocation.getArgument(3);
              sink.schema(SCHEMA);
              sink.row(ExprValueUtils.tupleValue(Map.of("message", "partial")));
              throw new IllegalArgumentException("Backend unavailable");
            });
    String id = submit("SELECT broken");
    JsonNode state = terminal(id, null);
    assertThat(state.get("state").asText()).isEqualTo("FAILED");
    assertThat(state.get("fileSizeBytes").asLong()).isZero();
    assertThat(state.get("error").asText()).contains("Backend unavailable");
    mvc.perform(get(ROOT + "/" + id + "/file")).andExpect(status().isConflict());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"query\":\" \"}",
        "{\"query\":\"SELECT 1\",\"language\":\"bad\"}",
        "{\"query\":\"SELECT 1\",\"source\":\" \"}",
        "null",
        "[]"
      })
  void rejectsInvalidRequests(String request) throws Exception {
    mvc.perform(post(ROOT).contentType(MediaType.APPLICATION_JSON).content(request))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(queries);
  }

  @Test
  void unknownAndMalformedIdsAreExplicit() throws Exception {
    mvc.perform(get(ROOT + "/bad")).andExpect(status().isBadRequest());
    mvc.perform(get(ROOT + "/" + QueryJobId.create("other").encode()))
        .andExpect(status().isNotFound());
  }

  private String submit(String query) throws Exception {
    var response =
        mvc.perform(
                post(ROOT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(Map.of("query", query))))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse();
    return json.readTree(response.getContentAsByteArray()).get("id").asText();
  }

  private JsonNode terminal(String id, String owner) throws Exception {
    long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < end) {
      var request = get(ROOT + "/" + id);
      if (owner != null) request.principal(() -> owner);
      JsonNode state =
          json.readTree(
              mvc.perform(request)
                  .andExpect(status().isOk())
                  .andReturn()
                  .getResponse()
                  .getContentAsByteArray());
      if (List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(state.get("state").asText()))
        return state;
      Thread.sleep(10);
    }
    throw new AssertionError("Export did not terminate");
  }
}
