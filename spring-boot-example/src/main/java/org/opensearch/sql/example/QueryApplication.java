/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example;

import java.util.Locale;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.executor.QueryType;
import org.opensearch.sql.lang.LangSpec;
import org.opensearch.sql.protocol.response.QueryResult;
import org.opensearch.sql.protocol.response.format.ExplainResponseJsonFormatter;
import org.opensearch.sql.protocol.response.format.JsonResponseFormatter.Style;
import org.opensearch.sql.protocol.response.format.SimpleJsonResponseFormatter;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class QueryApplication {
  public static void main(String[] args) {
    var arguments = new DefaultApplicationArguments(args);
    if (args.length == 0 || arguments.containsOption("help")) {
      System.out.println(
          """
          SQL/PPL example with Elasticsearch (logs) and ClickHouse (analytics).

          Set ES_PASSWORD and CLICKHOUSE_PASSWORD before running a query.
          Connection URLs and usernames can be overridden with ES_URL, ES_USER,
          CLICKHOUSE_JDBC_URL, CLICKHOUSE_USER and CLICKHOUSE_DATABASE.

          Run a query (SQL is the default language; logs is the default source):
            --source=logs --language=SQL '--query=SELECT count(*) AS total FROM your_index'
            --source=analytics --language=PPL '--query=source=your_table | stats count() as total'

          Show the execution plan for either language:
            --source=logs --language=SQL '--query=SELECT count(*) FROM your_index' --explain

          Run SQL and PPL counts on both data sources:
            --demo --es-table=your_index --clickhouse-table=your_table

          Table options name one index/table in its configured database.
          The application closes its connections and exits after execution.
          """);
      return;
    }
    validate(arguments);
    try (var context = SpringApplication.run(QueryApplication.class, args)) {
      /* CLI lifecycle. */
    }
  }

  @Bean
  ApplicationRunner query(UnifiedQueryService service) {
    return args -> {
      if (args.containsOption("demo")) {
        demo(service, "logs", required(args, "es-table"));
        demo(service, "analytics", required(args, "clickhouse-table"));
      } else {
        execute(
            service,
            option(args, "source", null),
            language(args),
            required(args, "query"),
            args.containsOption("explain"));
      }
    };
  }

  private static void demo(UnifiedQueryService service, String source, String table) {
    String identifier = "`" + table.replace("\\", "\\\\").replace("`", "``") + "`";
    System.out.printf("=== %s / SQL ===%n", source);
    execute(service, source, QueryType.SQL, "SELECT count(*) AS total FROM " + identifier, false);
    System.out.printf("=== %s / PPL ===%n", source);
    execute(
        service,
        source,
        QueryType.PPL,
        "source=" + identifier + " | stats count() as total",
        false);
  }

  private static void execute(
      UnifiedQueryService service,
      String source,
      QueryType language,
      String statement,
      boolean explain) {
    if (explain) {
      System.out.println(
          new ExplainResponseJsonFormatter(Style.PRETTY)
              .format(service.explain(source, language, statement)));
      return;
    }
    var response = service.execute(source, language, statement);
    if (response.getError() != null) {
      throw new IllegalStateException("Query execution failed", response.getError());
    }
    var result =
        new QueryResult(
            response.getSchema(),
            response.getResults(),
            response.getCursor(),
            LangSpec.fromLanguage(language.name()),
            response.getWarnings());
    System.out.println(new SimpleJsonResponseFormatter(Style.PRETTY).format(result));
  }

  private static void validate(ApplicationArguments args) {
    if (args.containsOption("demo")) {
      if (args.containsOption("query")
          || args.containsOption("source")
          || args.containsOption("language")
          || args.containsOption("explain")) {
        throw new IllegalArgumentException(
            "Use --demo with table options, or --query with source/language/--explain; see --help");
      }
      required(args, "es-table");
      required(args, "clickhouse-table");
    } else {
      required(args, "query");
      option(args, "source", null);
      language(args);
    }
  }

  private static QueryType language(ApplicationArguments args) {
    return QueryType.valueOf(option(args, "language", "SQL").toUpperCase(Locale.ROOT));
  }

  private static String required(ApplicationArguments args, String name) {
    String value = option(args, name, null);
    if (value == null) {
      throw new IllegalArgumentException("Missing --" + name + "; see --help");
    }
    return value;
  }

  private static String option(ApplicationArguments args, String name, String fallback) {
    if (!args.containsOption(name)) return fallback;
    var values = args.getOptionValues(name);
    if (values.size() != 1 || values.getFirst().isBlank()) {
      throw new IllegalArgumentException("Provide exactly one non-empty --" + name + " value");
    }
    return values.getFirst();
  }
}
