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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link MarkdownRenderer} against the shapes existdb-openapi's hover emits. */
class MarkdownRendererTest {

  private static final String HOVER = String.join("\n",
      "```xquery",
      "util:log($priority as xs:string, $message as item()*) as empty-sequence()",
      "```",
      "",
      "Logs the message to the current logger.",
      "",
      "**Parameters**",
      "",
      "- `$priority` (`xs:string`) — The logging priority",
      "- `$message` (`item()*`) — The message to log",
      "",
      "**Returns:** `empty-sequence()`");

  @Test
  void rendersCodeBlockBoldListAndInlineCode() {
    String html = MarkdownRenderer.toHtml(HOVER);
    assertTrue(html.startsWith("<html>"), "wrapped in html");
    assertTrue(html.contains("font-family:monospace"), "fenced block becomes a monospace block");
    assertTrue(html.contains("util:log($priority as xs:string"), "signature preserved");
    assertTrue(html.contains("<b>Parameters</b>"), "**Parameters** becomes bold");
    assertTrue(html.contains("<ul"), "bullets become a list");
    assertTrue(html.contains("<li>"), "bullet items");
    assertTrue(html.contains("monospace") && html.contains("$priority"), "inline code is monospace");
    // No stray Markdown markers left behind.
    assertFalse(html.contains("```"), "no leftover fences");
    assertFalse(html.contains("**Parameters**"), "no leftover bold markers");
  }

  @Test
  void escapesHtmlSpecialCharacters() {
    String html = MarkdownRenderer.toHtml("returns `item()*` and a < b");
    assertTrue(html.contains("a &lt; b"), "raw < is escaped");
    assertTrue(html.contains("item()*"), "inline code still rendered");
  }

  @Test
  void handlesPlainTextWithoutMarkers() {
    String html = MarkdownRenderer.toHtml("just text");
    assertTrue(html.contains("just text"));
  }
}
