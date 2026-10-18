/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api.completion;

import java.util.Locale;

/** An immutable editor snapshot. All editor offsets use UTF-16, not ANTLR code points. */
public record CompletionRequest(
    String language, String source, String text, int caret, long documentVersion, String trigger) {
  public static final int MAX_TEXT_LENGTH = 16_384;

  public CompletionRequest {
    if (language == null || language.isBlank())
      throw new IllegalArgumentException("Completion language is required");
    language = language.toLowerCase(Locale.ROOT);
    if (text == null || text.length() > MAX_TEXT_LENGTH)
      throw new IllegalArgumentException("Completion text must not exceed 16384 UTF-16 characters");
    if (caret < 0
        || caret > text.length()
        || caret > 0
            && caret < text.length()
            && Character.isHighSurrogate(text.charAt(caret - 1))
            && Character.isLowSurrogate(text.charAt(caret)))
      throw new IllegalArgumentException("Invalid completion caret");
    if (trigger == null) trigger = "input";
    if (!java.util.Set.of("input", "manual", "accept").contains(trigger))
      throw new IllegalArgumentException("Invalid completion trigger");
  }
}
