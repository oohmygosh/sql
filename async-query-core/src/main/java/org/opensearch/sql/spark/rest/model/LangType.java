/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.spark.rest.model;

import lombok.Getter;

/** Language type accepted in async query apis. */
@Getter
public enum LangType {
  SQL("sql"),
  PPL("ppl"),
  PROMQL("promql");
  private final String text;

  LangType(String text) {
    this.text = text;
  }

  /**
   * Get LangType from text.
   *
   * @param text text.
   * @return LangType {@link LangType}.
   */
  public static LangType fromString(String text) {
    for (LangType langType : LangType.values()) {
      if (langType.text.equalsIgnoreCase(text)) {
        return langType;
      }
    }
    throw new IllegalArgumentException("No LangType with text " + text + " found");
  }
}
