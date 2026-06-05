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

/** Tests the serialization-method → highlighting-language mapping. */
class ResultHighlighterTest {

  @Test
  void mapsMethodsToLanguages() {
    assertEquals(ResultHighlighter.Lang.JSON, ResultHighlighter.languageFor("json", "{}"));
    assertEquals(ResultHighlighter.Lang.XML, ResultHighlighter.languageFor("xml", "<a/>"));
    assertEquals(ResultHighlighter.Lang.XML, ResultHighlighter.languageFor("html5", "<p/>"));
    assertEquals(ResultHighlighter.Lang.NONE, ResultHighlighter.languageFor("text", "hi"));
  }

  @Test
  void adaptiveIsXmlForElementsAndXqueryOtherwise() {
    assertEquals(ResultHighlighter.Lang.XML,
        ResultHighlighter.languageFor("adaptive", "  <para>x</para>"));
    assertEquals(ResultHighlighter.Lang.XQUERY,
        ResultHighlighter.languageFor("adaptive", "map{ \"hello\": 1 }"));
  }
}
