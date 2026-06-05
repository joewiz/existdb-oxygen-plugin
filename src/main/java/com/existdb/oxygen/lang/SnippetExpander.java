/*
 * eXist-db Oxygen Plugin
 * Copyright (C) 2026 The eXist-db Authors
 *
 * info@exist-db.org
 * https://www.exist-db.org
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package com.existdb.oxygen.lang;

/**
 * Expands an LSP completion snippet ({@code insertTextFormat: 2}) — e.g.
 * {@code util:log(${1:\$priority}, ${2:\$message})} — into the plain text to insert plus the range of
 * the first tab stop to select, so accepting a function drops the caret onto its first argument.
 * Handles {@code ${n:default}}, {@code ${n}}, {@code $n}, choices {@code ${n|a,b|}}, the final stop
 * {@code $0}, and the escapes {@code \$ \} \\}. (Single-stop: full Tab-cycling is a later enhancement.)
 */
public final class SnippetExpander {

  /** The expanded text and the selection to apply ({@code selStart == selEnd} means just place the caret). */
  public record Expansion(String text, int selStart, int selEnd) {
  }

  private SnippetExpander() {
  }

  /** A parsed tab stop: index just after it in the snippet, its number, and where its text starts. */
  private record TabStop(int nextIndex, int number, int textStart) {
  }

  public static Expansion expand(String snippet) {
    StringBuilder out = new StringBuilder();
    int firstStopNumber = Integer.MAX_VALUE;
    int firstStopStart = -1;
    int firstStopEnd = -1;
    int finalStop = -1; // position of $0 / ${0}
    int i = 0;
    while (i < snippet.length()) {
      char c = snippet.charAt(i);
      if (c == '\\' && i + 1 < snippet.length()) {
        out.append(snippet.charAt(i + 1));
        i += 2;
        continue;
      }
      TabStop stop = c == '$' ? parseTabStop(snippet, i, out) : null;
      if (stop == null) {
        out.append(c);
        i++;
        continue;
      }
      if (stop.number() == 0) {
        finalStop = finalStop < 0 ? stop.textStart() : finalStop;
      } else if (stop.number() < firstStopNumber) {
        firstStopNumber = stop.number();
        firstStopStart = stop.textStart();
        firstStopEnd = out.length();
      }
      i = stop.nextIndex();
    }
    if (firstStopStart >= 0) {
      return new Expansion(out.toString(), firstStopStart, firstStopEnd);
    }
    int caret = finalStop >= 0 ? finalStop : out.length();
    return new Expansion(out.toString(), caret, caret);
  }

  /**
   * Parses a tab stop starting at {@code at} (where {@code snippet[at] == '$'}), appending any
   * default text to {@code out}. Returns the parsed {@link TabStop}, or {@code null} if {@code at}
   * doesn't begin a tab stop.
   */
  private static TabStop parseTabStop(String snippet, int at, StringBuilder out) {
    int i = at + 1;
    if (i < snippet.length() && Character.isDigit(snippet.charAt(i))) { // $n
      int start = i;
      while (i < snippet.length() && Character.isDigit(snippet.charAt(i))) {
        i++;
      }
      return new TabStop(i, Integer.parseInt(snippet.substring(start, i)), out.length());
    }
    if (i < snippet.length() && snippet.charAt(i) == '{') { // ${n} / ${n:default} / ${n|a,b|}
      return parseBraceStop(snippet, i, out);
    }
    return null;
  }

  private static TabStop parseBraceStop(String snippet, int brace, StringBuilder out) {
    int close = snippet.indexOf('}', brace);
    if (close < 0) {
      return null;
    }
    String body = snippet.substring(brace + 1, close);
    String digits = bodyNumber(body);
    if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) {
      return null;
    }
    int textStart = out.length();
    out.append(bodyDefault(body));
    return new TabStop(close + 1, Integer.parseInt(digits), textStart);
  }

  /** The leading {@code n} of a {@code ${...}} body (before any {@code :} or {@code |}). */
  private static String bodyNumber(String body) {
    int colon = body.indexOf(':');
    int bar = body.indexOf('|');
    if (colon >= 0) {
      return body.substring(0, colon);
    }
    return bar >= 0 ? body.substring(0, bar) : body;
  }

  /** The default text of a {@code ${...}} body: after {@code :}, or the first {@code |a,b|} choice. */
  private static String bodyDefault(String body) {
    int colon = body.indexOf(':');
    if (colon >= 0) {
      return unescape(body.substring(colon + 1));
    }
    int bar = body.indexOf('|');
    if (bar >= 0 && body.endsWith("|")) {
      String[] choices = body.substring(bar + 1, body.length() - 1).split(",");
      return choices.length > 0 ? unescape(choices[0]) : "";
    }
    return "";
  }

  private static String unescape(String s) {
    StringBuilder out = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\' && i + 1 < s.length()) {
        out.append(s.charAt(++i));
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }
}
