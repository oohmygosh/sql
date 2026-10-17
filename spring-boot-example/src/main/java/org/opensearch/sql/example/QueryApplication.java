/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example;

import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.executor.QueryType;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class QueryApplication {
  public static void main(String[] args) {
    try (var context = SpringApplication.run(QueryApplication.class, args)) {
      /* CLI lifecycle. */
    }
  }

  @Bean
  ApplicationRunner query(UnifiedQueryService service) {
    return args -> {
      if (!args.containsOption("query")) return;
      String language =
          args.containsOption("language") ? args.getOptionValues("language").getFirst() : "SQL";
      String source =
          args.containsOption("source") ? args.getOptionValues("source").getFirst() : null;
      var result =
          service.execute(
              source,
              QueryType.valueOf(language.toUpperCase(java.util.Locale.ROOT)),
              args.getOptionValues("query").getFirst());
      System.out.println(result);
    };
  }
}
