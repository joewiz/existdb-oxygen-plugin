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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link SnippetExpander} against existdb-openapi #45's function-arg snippets. */
class SnippetExpanderTest {

  @Test
  void expandsSingleArgAndSelectsIt() {
    // util:wait(${1:\$interval})  →  util:wait($interval) with $interval selected.
    SnippetExpander.Expansion e = SnippetExpander.expand("util:wait(${1:\\$interval})");
    assertEquals("util:wait($interval)", e.text());
    assertEquals("$interval", e.text().substring(e.selStart(), e.selEnd()));
  }

  @Test
  void firstTabStopIsSelectedWhenSeveral() {
    SnippetExpander.Expansion e =
        SnippetExpander.expand("util:log(${1:\\$priority}, ${2:\\$message})");
    assertEquals("util:log($priority, $message)", e.text());
    assertEquals("$priority", e.text().substring(e.selStart(), e.selEnd()));
  }

  @Test
  void emptyTabStopGivesZeroWidthCaret() {
    SnippetExpander.Expansion e = SnippetExpander.expand("fn:true($1)");
    assertEquals("fn:true()", e.text());
    assertEquals(e.selStart(), e.selEnd());
    assertEquals("fn:true(".length(), e.selStart());
  }

  @Test
  void plainTextWithNoTabStopsPlacesCaretAtEnd() {
    SnippetExpander.Expansion e = SnippetExpander.expand("fn:current-dateTime()");
    assertEquals("fn:current-dateTime()", e.text());
    assertEquals(e.text().length(), e.selStart());
    assertEquals(e.text().length(), e.selEnd());
  }

  @Test
  void unescapesLiteralDollarAndBrace() {
    SnippetExpander.Expansion e = SnippetExpander.expand("\\$x and \\}");
    assertEquals("$x and }", e.text());
  }
}
