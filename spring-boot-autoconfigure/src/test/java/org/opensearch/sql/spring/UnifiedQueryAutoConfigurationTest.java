/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.spring;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.junit.jupiter.api.Test;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.backend.QueryBackend;
import org.opensearch.sql.executor.CalciteExecutionEngine;
import org.opensearch.sql.executor.ExecutionEngine;
import org.opensearch.sql.executor.QueryType;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class UnifiedQueryAutoConfigurationTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(UnifiedQueryAutoConfiguration.class));

  static QueryBackend backend(String name, AtomicInteger closes) {
    return new QueryBackend() {
      public String name() {
        return name;
      }

      public String type() {
        return "test";
      }

      public Schema createSchema(SchemaPlus parent) {
        return new AbstractSchema();
      }

      public ExecutionEngine executionEngine() {
        return new CalciteExecutionEngine();
      }

      public void close() {
        closes.incrementAndGet();
      }
    };
  }

  @Test
  void worksWithoutAnyDatabaseDriverAndDoesNotLoadOpenSearch() {
    runner
        .withClassLoader(
            new FilteredClassLoader(
                "org.opensearch.sql.elasticsearch", "org.opensearch.sql.clickhouse"))
        .withBean(QueryBackend.class, () -> backend("custom", new AtomicInteger()))
        .run(
            context -> {
              assertThat(context).hasSingleBean(UnifiedQueryService.class);
              assertThat(context).hasSingleBean(org.opensearch.sql.job.QueryJobService.class);
              assertThat(
                      context
                          .getBean(UnifiedQueryService.class)
                          .execute(QueryType.SQL, "SELECT 1 AS n")
                          .getResults())
                  .hasSize(1);
              assertThatExceptionOfType(ClassNotFoundException.class)
                  .isThrownBy(() -> Class.forName("org.opensearch.client.RestHighLevelClient"));
            });
  }

  @Test
  void customFactoryIsExtensibleAndOwnedBackendCloses() {
    AtomicInteger closes = new AtomicInteger();
    runner
        .withBean(
            QueryBackendFactory.class,
            () ->
                new QueryBackendFactory() {
                  public String type() {
                    return "test";
                  }

                  public QueryBackend create(
                      String name, UnifiedQueryProperties.DataSource config) {
                    return backend(name, closes);
                  }
                })
        .withPropertyValues(
            "unified-query.data-sources.demo.type=test", "unified-query.default-data-source=demo")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(
                      context
                          .getBean(UnifiedQueryService.class)
                          .execute(QueryType.SQL, "SELECT 1")
                          .getResults())
                  .hasSize(1);
            });
    assertThat(closes.get()).isEqualTo(1);
  }

  @Test
  void missingDriverProducesActionableConfigurationFailure() {
    runner
        .withClassLoader(new FilteredClassLoader("org.opensearch.sql.elasticsearch"))
        .withPropertyValues(
            "unified-query.data-sources.logs.type=elasticsearch",
            "unified-query.data-sources.logs.url=http://localhost:9200")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .hasRootCauseMessage(
                        "No backend factory for elasticsearch; add its backend dependency or a"
                            + " factory bean"));
  }

  @Test
  void userServiceOverridesDefault() {
    UnifiedQueryService custom =
        new UnifiedQueryService(List.of(backend("test", new AtomicInteger())), null);
    runner
        .withBean(UnifiedQueryService.class, () -> custom)
        .run(context -> assertThat(context.getBean(UnifiedQueryService.class)).isSameAs(custom));
  }

  @Test
  void userJobServiceOverridesLocalHost() {
    var jobs = org.mockito.Mockito.mock(org.opensearch.sql.job.QueryJobService.class);
    runner
        .withBean(QueryBackend.class, () -> backend("custom", new AtomicInteger()))
        .withBean(org.opensearch.sql.job.QueryJobService.class, () -> jobs)
        .run(
            context ->
                assertThat(context.getBean(org.opensearch.sql.job.QueryJobService.class))
                    .isSameAs(jobs));
  }

  @Test
  void userBackendOverridesConfiguredSourceWithoutDoubleOwnership() {
    AtomicInteger closes = new AtomicInteger();
    runner
        .withBean(QueryBackend.class, () -> backend("logs", closes))
        .withPropertyValues("unified-query.data-sources.logs.type=not-installed")
        .run(context -> assertThat(context).hasNotFailed());
    assertThat(closes.get()).isEqualTo(1);
  }
}
