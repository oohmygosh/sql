/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.example.QueryApplication;
import org.opensearch.sql.example.service.QueryService;
import org.opensearch.sql.spring.ConfiguredQueryBackends;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = QueryApplication.class)
@AutoConfigureMockMvc
@MockitoBean(types = {ConfiguredQueryBackends.class, UnifiedQueryService.class})
class SearchUiControllerTest {
  @Autowired MockMvc mvc;
  @MockitoBean QueryService queries;

  @Test
  void configExposesOnlyDisplayMetadata() throws Exception {
    String body =
        mvc.perform(get("/ui/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.defaultSource").value("logs"))
            .andExpect(jsonPath("$.sources[0].name").value("logs"))
            .andExpect(jsonPath("$.sources[0].type").value("elasticsearch"))
            .andExpect(jsonPath("$.sources[0].table").value("logevent-*"))
            .andExpect(jsonPath("$.sources[1].name").value("analytics"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain("password", "username", "url", "properties", "192.168.");
  }

  @Test
  void servesBundledFrontend() throws Exception {
    mvc.perform(get("/index.html"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"query-input\"")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("./assets/")));
  }

  @Test
  void sendsPublicQueryErrorsToUiAndHidesInternalFailureDetails() throws Exception {
    when(queries.execute(any())).thenThrow(new IllegalArgumentException("Unknown field: missing"));
    mvc.perform(post("/query").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"bad\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Unknown field: missing"));
    doThrow(new IllegalStateException("internal connection secret")).when(queries).execute(any());
    mvc.perform(post("/query").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"bad\"}"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.message").value("Query execution failed"));
  }
}
