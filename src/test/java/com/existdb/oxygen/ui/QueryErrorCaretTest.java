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
package com.existdb.oxygen.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Coordinate mapping for caret-jump: {@code (line, column)} → character offset within the query. */
class QueryErrorCaretTest {

  @Test
  void firstLineColumnMapsToZeroBasedOffset() {
    // "1 to" errors at line 1, column 5 (EOF after "1 to"); column 5 (1-based) → offset 4.
    assertEquals(4, QueryErrorCaret.offsetWithin("1 to", 1, 5));
  }

  @Test
  void columnOneIsTheLineStart() {
    assertEquals(0, QueryErrorCaret.offsetWithin("abc", 1, 1));
  }

  @Test
  void laterLinesCountNewlines() {
    // Two leading blank lines push the error to line 3; "\n\n1 to" → line 3 col 5 → offset 6.
    assertEquals(6, QueryErrorCaret.offsetWithin("\n\n1 to", 3, 5));
    // "ab\ncde": line 2 ("cde") starts at offset 3, so column 3 ('e') → offset 5.
    assertEquals(5, QueryErrorCaret.offsetWithin("ab\ncde", 2, 3));
  }

  @Test
  void lineBeyondTextReturnsMinusOne() {
    assertEquals(-1, QueryErrorCaret.offsetWithin("only one line", 2, 1));
  }

  @Test
  void nonPositiveColumnClampsToLineStart() {
    assertEquals(0, QueryErrorCaret.offsetWithin("abc", 1, 0));
    assertEquals(3, QueryErrorCaret.offsetWithin("ab\ncde", 2, 0));
  }
}
