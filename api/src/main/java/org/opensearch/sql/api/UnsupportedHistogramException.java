/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api;

/** A valid query whose semantics do not permit an auxiliary source-time distribution. */
public final class UnsupportedHistogramException extends UnsupportedOperationException {
  public UnsupportedHistogramException() {
    super("当前查询不支持自动生成时间分布");
  }
}
