/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.spring;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Connection settings only; query settings retain their existing engine names and defaults. */
@Getter
@Setter
@ConfigurationProperties("unified-query")
public class UnifiedQueryProperties {
  private String defaultDataSource;
  private Map<String, Object> settings = new LinkedHashMap<>();
  private Map<String, DataSource> dataSources = new LinkedHashMap<>();

  @Getter
  @Setter
  public static class DataSource {
    private String type;
    private String url;
    private String username;
    private String password;
    private String database;
    private Map<String, String> fieldTypes = new LinkedHashMap<>();
    private Map<String, String> properties = new LinkedHashMap<>();
  }
}
