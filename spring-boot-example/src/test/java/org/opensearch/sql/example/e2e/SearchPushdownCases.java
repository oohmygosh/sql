/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.e2e;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.opensearch.sql.api.QueryExecutionRequest.FieldFilter;
import org.opensearch.sql.example.model.SearchRequest;
import org.opensearch.sql.executor.QueryType;

/** The 57 independently named cases from the HTTP/ES pushdown acceptance audit. */
final class SearchPushdownCases {
  static final long START = 1792247588208L;
  static final long END = START + 3600000;
  private static final String EQ = "`extracted.process.name`='sshd'";
  private static final String ORDER = " | sort 0 -timestamp,-id";

  enum Expectation {
    ALL,
    PARTIAL,
    LOCAL_OR,
    REJECT,
    BOUNDED,
    AFTER_HEAD,
    AGGREGATE,
    LOCAL_AGGREGATE,
    LOCAL_TRANSFORM
  }

  record Case(
      String id,
      String query,
      List<FieldFilter> filters,
      int page,
      int size,
      Expectation expectation,
      boolean histogram) {
    SearchRequest request() {
      return new SearchRequest(
          "logs",
          query.startsWith("SELECT") ? QueryType.SQL : QueryType.PPL,
          query,
          new SearchRequest.TimeRange(
              "timestamp",
              Instant.ofEpochMilli(START).toString(),
              Instant.ofEpochMilli(END).toString(),
              "Asia/Shanghai"),
          filters,
          new SearchRequest.Page(page, size),
          histogram ? new SearchRequest.Histogram("1m") : null,
          histogram ? List.of("rows", "histogram") : List.of("rows"));
    }
  }

  private SearchPushdownCases() {}

  private static void add(List<Case> cases, String id, String query, Expectation expectation) {
    cases.add(new Case(id, query, List.of(), 1, 5, expectation, false));
  }

  static List<Case> cases(String index) {
    String src = "source=`" + index + "`";
    List<Case> cases = new ArrayList<>();
    String pass = "message contains 'PASS'";
    add(cases, "ppl_original", src + " | where " + EQ + " and " + pass, Expectation.ALL);
    add(cases, "ppl_split_where", src + " | where " + EQ + " | where " + pass, Expectation.ALL);
    add(cases, "ppl_sort_before", src + ORDER + " | where " + EQ + " and " + pass, Expectation.ALL);
    cases.add(
        new Case(
            "structured_eq",
            src + " | where " + pass,
            List.of(new FieldFilter("extracted.process.name", "eq", "sshd")),
            1,
            5,
            Expectation.ALL,
            false));
    cases.add(
        new Case(
            "structured_contains",
            src,
            List.of(
                new FieldFilter("extracted.process.name", "eq", "sshd"),
                new FieldFilter("message", "contains", "PASS")),
            1,
            5,
            Expectation.ALL,
            false));
    add(
        cases,
        "ppl_explicit_wc",
        src + " | where " + EQ + " and `message.wc` contains 'PASS'",
        Expectation.ALL);
    add(
        cases,
        "ppl_keyword_ignore_above",
        src + " | where " + EQ + " and `message.keyword` contains 'PASS'",
        Expectation.PARTIAL);
    add(
        cases,
        "ppl_mixed_length",
        src + " | where " + EQ + " and length(message)>20" + ORDER,
        Expectation.PARTIAL);
    add(
        cases,
        "ppl_mixed_local_text",
        src + " | where " + EQ + " and local_message contains 'PASS'" + ORDER,
        Expectation.PARTIAL);
    add(cases, "ppl_range", src + " | where quantity>=3 and quantity<15", Expectation.ALL);
    add(cases, "ppl_in", src + " | where quantity in (2,4,8,16)", Expectation.ALL);
    add(cases, "ppl_between", src + " | where quantity between 3 and 9", Expectation.ALL);
    add(cases, "ppl_not_in", src + " | where quantity not in (2,4,8,16)", Expectation.ALL);
    add(cases, "ppl_not_equal", src + " | where level!='INFO'", Expectation.ALL);
    add(
        cases,
        "ppl_not_contains",
        src + " | where " + EQ + " and not (message contains 'PASS')",
        Expectation.ALL);
    add(cases, "ppl_null", src + " | where isnull(level)", Expectation.ALL);
    add(cases, "ppl_not_null", src + " | where isnotnull(level)", Expectation.ALL);
    add(cases, "ppl_bool", src + " | where enabled=true", Expectation.ALL);
    add(
        cases,
        "ppl_or_native",
        src + " | where " + EQ + " or message contains 'PASS'",
        Expectation.ALL);
    add(
        cases,
        "ppl_or_mixed",
        src + " | where " + EQ + " or local_message contains 'PASS'" + ORDER,
        Expectation.LOCAL_OR);
    add(cases, "ppl_keyword_ilike", src + " | where label ilike 'LABEL-%'", Expectation.ALL);
    add(cases, "ppl_regex_literal", src + " | where regexp_match(message,'PASS')", Expectation.ALL);
    add(
        cases,
        "ppl_regex_complex",
        src + " | where " + EQ + " and regexp_match(message,'P[A-Z]+')" + ORDER,
        Expectation.PARTIAL);
    add(
        cases,
        "ppl_fields_first",
        src
            + " | fields id,timestamp,message,extracted | where extracted.process.name='sshd' and "
            + pass
            + ORDER,
        Expectation.ALL);
    add(
        cases,
        "ppl_fields_first_quoted_segments",
        src
            + " | fields id,timestamp,message,extracted | where `extracted`.`process`.`name`='sshd'"
            + " and "
            + pass
            + ORDER,
        Expectation.ALL);
    add(
        cases,
        "ppl_fields_first_retained_flat",
        src
            + " | fields id,timestamp,message,`extracted.process.name` | where "
            + EQ
            + " and "
            + pass
            + ORDER,
        Expectation.ALL);
    add(
        cases,
        "ppl_quoted_flat_after_removal_rejected",
        src + " | fields id,timestamp,message,extracted | where " + EQ,
        Expectation.REJECT);
    add(
        cases,
        "ppl_fields_last",
        src + " | where " + EQ + " and " + pass + ORDER + " | fields id,message",
        Expectation.ALL);
    add(
        cases,
        "ppl_rename",
        src
            + " | rename message as text | where "
            + EQ
            + " and text contains 'PASS'"
            + ORDER
            + " | fields id,text",
        Expectation.ALL);
    add(
        cases,
        "ppl_eval_alias",
        src
            + " | eval text=message | where "
            + EQ
            + " and text contains 'PASS'"
            + ORDER
            + " | fields id,text",
        Expectation.ALL);
    add(
        cases,
        "ppl_eval_lower",
        src
            + " | eval text=lower(message) | where "
            + EQ
            + " and text contains 'pass'"
            + ORDER
            + " | fields id,text",
        Expectation.PARTIAL);
    add(
        cases,
        "ppl_eval_length",
        src
            + " | where "
            + EQ
            + " | eval n=length(message) | where n>20"
            + ORDER
            + " | fields id,n",
        Expectation.PARTIAL);
    add(
        cases,
        "ppl_sort_asc",
        src + " | where " + EQ + " | sort 0 +quantity,+id | fields id,quantity",
        Expectation.ALL);
    add(
        cases,
        "ppl_sort_multi",
        src + " | where " + EQ + " | sort 0 -level,+quantity | fields id,level,quantity",
        Expectation.ALL);
    cases.add(
        new Case(
            "ppl_page_two",
            src + " | where " + EQ + " and " + pass,
            List.of(),
            2,
            3,
            Expectation.ALL,
            false));
    add(
        cases,
        "ppl_head_after_filter",
        src + " | where " + EQ + " and " + pass + ORDER + " | head 3",
        Expectation.BOUNDED);
    add(
        cases,
        "ppl_head_before_filter",
        src + ORDER + " | head 3 | where " + pass,
        Expectation.AFTER_HEAD);
    add(
        cases,
        "ppl_stats_count",
        src + " | where " + EQ + " | stats count() as total",
        Expectation.AGGREGATE);
    add(
        cases,
        "ppl_stats_group",
        src + " | where " + EQ + " | stats count() as total by level | sort level",
        Expectation.AGGREGATE);
    add(
        cases,
        "ppl_stats_nested_group",
        src
            + " | stats count() as total by `extracted.process.name` | sort"
            + " `extracted.process.name`",
        Expectation.AGGREGATE);
    add(
        cases,
        "ppl_stats_metrics",
        src
            + " | where "
            + EQ
            + " | stats count(amount) as n,avg(amount) as a,min(amount) as lo,max(amount) as"
            + " hi,sum(amount) as total",
        Expectation.AGGREGATE);
    add(
        cases,
        "ppl_stats_long_sum_local",
        src + " | where " + EQ + " | stats sum(large) as total",
        Expectation.LOCAL_AGGREGATE);
    add(
        cases,
        "ppl_stats_text_group_local",
        src + " | where " + EQ + " | stats count() as total by message | sort message",
        Expectation.LOCAL_AGGREGATE);
    add(
        cases,
        "ppl_stats_numeric_span",
        src
            + " | where "
            + EQ
            + " | stats count() as total by span(quantity,10) as bucket | sort bucket",
        Expectation.AGGREGATE);
    add(
        cases,
        "ppl_stats_time_span",
        src
            + " | where "
            + EQ
            + " | stats count() as total by span(timestamp,5m) as bucket | sort bucket",
        Expectation.AGGREGATE);
    add(
        cases,
        "ppl_stats_after_residual",
        src + " | where " + EQ + " and length(message)>20 | stats count() as total",
        Expectation.LOCAL_AGGREGATE);
    add(
        cases,
        "ppl_dedup",
        src + " | where " + EQ + ORDER + " | dedup level | fields id,level",
        Expectation.LOCAL_TRANSFORM);
    add(
        cases,
        "sql_original",
        "SELECT id,message FROM `"
            + index
            + "` WHERE `extracted.process.name`='sshd' AND message LIKE '%PASS%' ORDER BY timestamp"
            + " DESC,id DESC",
        Expectation.ALL);
    add(
        cases,
        "sql_range_in",
        "SELECT id,quantity FROM `"
            + index
            + "` WHERE quantity BETWEEN 3 AND 20 AND level IN ('INFO','ERROR') ORDER BY"
            + " quantity,id",
        Expectation.ALL);
    add(
        cases,
        "sql_or_null",
        "SELECT id,level FROM `"
            + index
            + "` WHERE level IS NULL OR level='ERROR' ORDER BY timestamp DESC,id DESC",
        Expectation.ALL);
    add(
        cases,
        "sql_alias",
        "SELECT id,message AS text FROM `"
            + index
            + "` WHERE `extracted.process.name`='sshd' AND message LIKE '%PASS%' ORDER BY timestamp"
            + " DESC,id DESC",
        Expectation.ALL);
    add(
        cases,
        "sql_group_having",
        "SELECT level,count(*) AS total FROM `"
            + index
            + "` WHERE quantity>=2 GROUP BY level HAVING count(*)>1 ORDER BY level",
        Expectation.AGGREGATE);
    add(
        cases,
        "sql_limit_offset",
        "SELECT id,message FROM `"
            + index
            + "` WHERE message LIKE '%PASS%' ORDER BY timestamp DESC,id DESC LIMIT 3 OFFSET 2",
        Expectation.BOUNDED);
    add(
        cases,
        "sql_conditional_numeric_count",
        "SELECT count(CASE WHEN quantity>10 THEN 1 END) AS total FROM `" + index + "`",
        Expectation.AGGREGATE);
    add(
        cases,
        "sql_conditional_text_count",
        "SELECT count(CASE WHEN message LIKE '%PASS%' THEN 1 END) AS total FROM `" + index + "`",
        Expectation.AGGREGATE);
    add(
        cases,
        "sql_unsafe_long_sum",
        "SELECT sum(large) AS total FROM `" + index + "` WHERE quantity>3",
        Expectation.LOCAL_AGGREGATE);
    cases.add(
        new Case(
            "ppl_http_histogram",
            src + " | where " + EQ + " and " + pass,
            List.of(),
            1,
            5,
            Expectation.ALL,
            true));
    return List.copyOf(cases);
  }
}
