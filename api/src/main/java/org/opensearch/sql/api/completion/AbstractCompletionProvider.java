/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api.completion;

import static org.opensearch.sql.api.completion.CompletionMetadata.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Token;
import org.opensearch.sql.api.completion.CompletionResponse.Item;
import org.opensearch.sql.api.completion.CompletionResponse.TextEdit;

/** Shared editor mechanics; language adapters retain grammar and scope policy. */
public abstract class AbstractCompletionProvider implements LanguageCompletionProvider {
  protected abstract static class CompletionSession {
    final CompletionRequest request;
    final CompletionMetadata metadata;
    final String text;
    final int caret;
    final Scan scan;
    final List<Item> items = new ArrayList<>();
    Cursor cursor;
    boolean incomplete;
    String message = "";

    CompletionSession(
        CompletionRequest request, CompletionMetadata metadata, Scan scan, Cursor cursor) {
      this.request = request;
      this.metadata = metadata;
      this.text = request.text();
      this.caret = request.caret();
      this.scan = scan;
      this.cursor = cursor;
    }

    int completionPriority(Item item) {
      return item.kind().equals("field") || item.kind().equals("table") ? 0 : 1;
    }

    CompletionResponse response() {
      LinkedHashMap<String, Item> unique = new LinkedHashMap<>();
      for (Item item : items)
        unique.putIfAbsent(
            item.label() + ":" + item.textEdit().from() + ":" + item.textEdit().newText(), item);
      return new CompletionResponse(
          request.documentVersion(),
          unique.values().stream()
              .sorted(Comparator.comparingInt(this::completionPriority))
              .toList(),
          incomplete,
          message);
    }

    void insertion() {
      cursor = new Cursor(caret, caret, "");
    }

    void keyword(String label, String detail) {
      add(label, "keyword", detail, label + " ", true);
    }

    void value(String label, String detail) {
      add(label, "value", detail, label, false);
    }

    void symbol(String label, String detail, String inserted) {
      add(label, "operator", detail, inserted, false);
    }

    void add(String label, String kind, String detail, String inserted, boolean separate) {
      if (!label.toLowerCase(Locale.ROOT).startsWith(cursor.prefix.toLowerCase(Locale.ROOT)))
        return;
      int from = cursor.from, to = cursor.to;
      if (from == caret
          && to == caret
          && !text.substring(caret).isBlank()
          && (kind.equals("operator")
              || Set.of("and", "or", "as", "by", "asc", "desc").contains(label))
          && scan.visible.stream()
              .filter(token -> token.start >= caret)
              .findFirst()
              .map(token -> token.is(label))
              .orElse(false)) return;
      boolean whitespace = from > 0 && Character.isWhitespace(text.charAt(from - 1));
      if (from > 0 && whitespace && inserted.startsWith(" ")) inserted = inserted.substring(1);
      if (separate && from > 0 && !whitespace && text.charAt(from - 1) != '|')
        inserted = " " + inserted;
      if (from == to
          && from > 0
          && !inserted.isEmpty()
          && identifierBoundary(text.charAt(from - 1))
          && identifierBoundary(inserted.charAt(0))) inserted = " " + inserted;
      if (to < text.length() && Character.isWhitespace(text.charAt(to)) && inserted.endsWith(" "))
        inserted = inserted.substring(0, inserted.length() - 1);
      items.add(new Item(label, kind, detail, new TextEdit(from, to, inserted), null));
    }
  }

  protected static int fieldRank(Field field) {
    if (field.path().size() > 1 || field.name().contains(".")) return 2;
    return field.name().startsWith("_") ? 1 : 0;
  }

  protected static boolean identifierBoundary(char character) {
    return Character.isLetterOrDigit(character)
        || character == '_'
        || character == '@'
        || character == '`'
        || character == '\''
        || character == '"';
  }

  protected static boolean identifierToken(Tok token) {
    return !token.text.isEmpty()
        && (Character.isLetter(token.text.codePointAt(0))
            || token.text.startsWith("`")
            || token.text.startsWith("_")
            || token.text.startsWith("@"));
  }

  protected static Scan scan(String text, Lexer lexer) {
    int[] offsets = offsets(text);
    lexer.removeErrorListeners();
    List<Tok> all = new ArrayList<>(), visible = new ArrayList<>();
    int depth = 0;
    boolean tooDeep = false;
    for (Token token : lexer.getAllTokens()) {
      Tok item =
          new Tok(
              token.getType(),
              token.getText(),
              offsets[token.getStartIndex()],
              offsets[token.getStopIndex() + 1],
              token.getChannel());
      all.add(item);
      if (token.getChannel() == Token.DEFAULT_CHANNEL) {
        visible.add(item);
        if (item.is("(") || item.is("[")) {
          depth++;
          if (depth > 64) tooDeep = true;
        } else if (item.is(")") || item.is("]")) depth = Math.max(0, depth - 1);
      }
    }
    return new Scan(all, visible, tooDeep);
  }

  protected static int[] offsets(String text) {
    int count = text.codePointCount(0, text.length());
    int[] result = new int[count + 1];
    for (int index = 0, position = 0; index < count; index++) {
      position += Character.charCount(text.codePointAt(position));
      result[index + 1] = position;
    }
    return result;
  }

  protected static Cursor cursor(
      Scan scan, String text, int caret, Predicate<Tok> literal, List<String> lineComments) {
    // Scan quotation boundaries too: an unfinished string may be emitted as individual lexer
    // errors.
    for (int index = 0; index < text.length(); ) {
      int start = index;
      if (startsComment(text, index, lineComments)) {
        int end = text.indexOf('\n', index);
        if (end < 0) end = text.length();
        if (caret > start && caret <= end) return null;
        index = end;
      } else if (text.startsWith("/*", index)) {
        int close = text.indexOf("*/", index + 2);
        int end = close < 0 ? text.length() : close + 2;
        if (caret > start && (caret < end || close < 0 && caret == end)) return null;
        index = end;
      } else if (text.charAt(index) == '\''
          || text.charAt(index) == '"'
          || text.charAt(index) == '`') {
        char quote = text.charAt(index++);
        boolean closed = false;
        while (index < text.length()) {
          char character = text.charAt(index++);
          if (character == '\\') {
            if (index < text.length()) index++;
          } else if (character == quote) {
            if (index < text.length() && text.charAt(index) == quote) index++;
            else {
              closed = true;
              break;
            }
          }
        }
        if (caret > start && (caret < index || !closed && caret == index))
          return quote == '`'
              ? new Cursor(start, index, unquote(text.substring(start, caret)))
              : null;
        if (quote == '`' && caret == index)
          return new Cursor(start, index, unquote(text.substring(start, caret)));
      } else index++;
      if (index > caret) break;
    }
    for (Tok token : scan.visible) {
      if (caret <= token.end
          && token.start < caret
          && !literal.test(token)
          && !token.text.isEmpty()
          && (Character.isLetter(token.text.codePointAt(0))
              || token.text.charAt(0) == '_'
              || token.text.charAt(0) == '@'))
        return new Cursor(token.start, token.end, unquote(text.substring(token.start, caret)));
    }
    return new Cursor(caret, caret, "");
  }

  protected static int identifierEnd(Scan scan, int start, int caret) {
    int end = caret;
    for (Tok token : scan.visible) {
      if (token.start < start || token.end <= caret) continue;
      if (token.start <= end
          && !Set.of("|", ",", "=", ">", "<", "!=", ">=", "<=", "(", ")", "[", "]")
              .contains(token.text)) end = token.end;
      else break;
    }
    return end;
  }

  protected static int qualifiedIdentifierEnd(Scan scan, int start, int caret) {
    int end = caret;
    boolean afterDot = false;
    for (Tok token : scan.visible) {
      if (token.start < start || token.end <= caret) continue;
      if (token.start > end) break;
      if (token.start < caret && identifierToken(token)) end = token.end;
      else if (token.start == end && token.is(".")) {
        afterDot = true;
        end = token.end;
      } else if (token.start == end && afterDot && identifierToken(token)) {
        afterDot = false;
        end = token.end;
      } else break;
    }
    return end;
  }

  protected static String quote(String value) {
    return "`" + value.replace("`", "``") + "`";
  }

  protected static String unquote(String value) {
    List<String> parts = identifierParts(value);
    return String.join(".", parts);
  }

  protected static List<String> identifierParts(String value) {
    List<String> parts = new ArrayList<>();
    StringBuilder part = new StringBuilder();
    boolean quoted = false;
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character == '`') {
        if (quoted && index + 1 < value.length() && value.charAt(index + 1) == '`') {
          part.append('`');
          index++;
        } else quoted = !quoted;
      } else if (character == '\\' && quoted && index + 1 < value.length())
        part.append(value.charAt(++index));
      else if (character == '.' && !quoted) {
        parts.add(part.toString());
        part.setLength(0);
      } else part.append(character);
    }
    parts.add(part.toString());
    return parts;
  }

  private static boolean startsComment(String text, int index, List<String> prefixes) {
    return prefixes.stream().anyMatch(prefix -> text.startsWith(prefix, index));
  }

  protected static final class Cursor {
    final int from, to;
    final String prefix;

    Cursor(int from, int to, String prefix) {
      this.from = from;
      this.to = to;
      this.prefix = prefix;
    }
  }

  protected static final class Tok {
    final int type, start, end, channel;
    final String text;

    Tok(int type, String text, int start, int end, int channel) {
      this.type = type;
      this.text = text;
      this.start = start;
      this.end = end;
      this.channel = channel;
    }

    String text() {
      return text;
    }

    int type() {
      return type;
    }

    boolean is(String value) {
      return text.equalsIgnoreCase(value);
    }

    String lower() {
      return text.toLowerCase(Locale.ROOT);
    }
  }

  protected static final class Scan {
    final List<Tok> all, visible;
    final boolean tooDeep;

    Scan(List<Tok> all, List<Tok> visible, boolean tooDeep) {
      this.all = all;
      this.visible = visible;
      this.tooDeep = tooDeep;
    }
  }
}
