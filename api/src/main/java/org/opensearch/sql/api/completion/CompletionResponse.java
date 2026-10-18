/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api.completion;

import java.util.List;

/** Language-neutral suggestions. The editor applies text edits without adding quoting or spaces. */
public record CompletionResponse(
    long documentVersion, List<Item> items, boolean incomplete, String message) {
  public CompletionResponse {
    items = List.copyOf(items);
  }

  public record Item(
      String label, String kind, String detail, TextEdit textEdit, Integer caretOffset) {}

  /**
   * A replacement of [from, to); caretOffset, when present, is relative to from after insertion.
   */
  public record TextEdit(int from, int to, String newText) {}
}
