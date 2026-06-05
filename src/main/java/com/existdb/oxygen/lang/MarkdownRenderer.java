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
 * A tiny, dependency-free Markdown → HTML renderer for the small documentation snippets
 * existdb-openapi's LSP endpoints return (PR #44): fenced code blocks, {@code **bold**}, inline
 * {@code `code`}, and {@code -} bullet lists. It targets Swing's HTML 3.2 ({@link javax.swing.JEditorPane}),
 * not a full CommonMark implementation — just enough to render a function's signature/Parameters/Returns
 * card legibly. Pure and Oxygen-free so it stays unit-testable.
 */
public final class MarkdownRenderer {

  private MarkdownRenderer() {
  }

  /** Renders {@code markdown} as an HTML document body for a Swing {@code JEditorPane}. */
  public static String toHtml(String markdown) {
    String source = markdown == null ? "" : markdown;
    StringBuilder out = new StringBuilder(
        "<html><body style='font-family:sans-serif;font-size:11px;margin:7px'>");
    State state = new State();
    for (String line : source.split("\n", -1)) {
      appendLine(out, state, line);
    }
    closeList(out, state);
    if (state.inCode && state.code.length() > 0) { // unterminated fence — emit what we have
      appendCode(out, state.code);
    }
    return out.append("</body></html>").toString();
  }

  /** Mutable line-by-line render state (whether we're inside a fenced block or a bullet list). */
  private static final class State {
    private boolean inCode;
    private boolean inList;
    private final StringBuilder code = new StringBuilder();
  }

  private static void appendLine(StringBuilder out, State state, String line) {
    if (line.stripLeading().startsWith("```")) {
      if (state.inCode) {
        appendCode(out, state.code);
        state.code.setLength(0);
      }
      state.inCode = !state.inCode;
      return;
    }
    if (state.inCode) {
      state.code.append(line).append('\n');
      return;
    }
    String text = line.strip();
    if (text.isEmpty()) {
      closeList(out, state);
      return;
    }
    if (text.startsWith("- ") || text.startsWith("* ")) {
      if (!state.inList) {
        out.append("<ul style='margin:2px 0 2px 16px'>");
        state.inList = true;
      }
      out.append("<li>").append(inline(text.substring(2))).append("</li>");
      return;
    }
    closeList(out, state);
    out.append("<div style='margin:3px 0'>").append(inline(text)).append("</div>");
  }

  private static void appendCode(StringBuilder out, StringBuilder code) {
    // A monospace block that *wraps* (unlike <pre>), so a long signature reflows instead of forcing
    // a horizontal scrollbar in the hover popup. Internal newlines are kept as <br>.
    out.append("<div style='font-family:monospace;font-size:11px;margin:2px 0'>")
        .append(escape(code.toString().stripTrailing()).replace("\n", "<br>")).append("</div>");
  }

  private static void closeList(StringBuilder out, State state) {
    if (state.inList) {
      out.append("</ul>");
      state.inList = false;
    }
  }

  /** Inline formatting: HTML-escape, then apply {@code `code`} then {@code **bold**}. */
  private static String inline(String text) {
    StringBuilder out = new StringBuilder();
    String escaped = escape(text);
    int i = 0;
    while (i < escaped.length()) {
      char c = escaped.charAt(i);
      if (c == '`') {
        int end = escaped.indexOf('`', i + 1);
        if (end > i) {
          // An explicit 11px monospace span (not <code>, whose Swing default renders smaller than
          // the body text, making surrounding words look oversized).
          out.append("<span style='font-family:monospace;font-size:11px'>")
              .append(escaped, i + 1, end).append("</span>");
          i = end + 1;
          continue;
        }
      } else if (c == '*' && i + 1 < escaped.length() && escaped.charAt(i + 1) == '*') {
        int end = escaped.indexOf("**", i + 2);
        if (end > i) {
          out.append("<b>").append(escaped, i + 2, end).append("</b>");
          i = end + 2;
          continue;
        }
      }
      out.append(c);
      i++;
    }
    return out.toString();
  }

  private static String escape(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
