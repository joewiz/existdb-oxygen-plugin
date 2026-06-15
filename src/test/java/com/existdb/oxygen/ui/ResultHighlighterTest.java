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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.StyleConstants;

import org.junit.jupiter.api.Test;

/** Tests the serialization-method → highlighting-language mapping and full-text match highlighting. */
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

  @Test
  void highlightMatchesStripsTagsAndPaintsTheMatch() throws Exception {
    DefaultStyledDocument doc = new DefaultStyledDocument();
    ResultHighlighter.apply(doc, "<a>foo <exist:match>bar</exist:match> baz</a>",
        ResultHighlighter.Lang.XML, true);
    String text = doc.getText(0, doc.getLength());
    assertFalse(text.contains("exist:match")); // the match tags are hidden
    assertTrue(text.contains("foo bar baz"));   // the wrapped text remains
    // "bar" carries a background; the surrounding text does not.
    int bar = text.indexOf("bar");
    int foo = text.indexOf("foo");
    assertNotNull(doc.getCharacterElement(bar).getAttributes().getAttribute(StyleConstants.Background));
    assertNull(doc.getCharacterElement(foo).getAttributes().getAttribute(StyleConstants.Background));
  }

  @Test
  void highlightDisabledKeepsTheRawMarkup() throws Exception {
    DefaultStyledDocument doc = new DefaultStyledDocument();
    ResultHighlighter.apply(doc, "<a><exist:match>bar</exist:match></a>",
        ResultHighlighter.Lang.XML, false);
    assertTrue(doc.getText(0, doc.getLength()).contains("exist:match")); // raw tags kept
  }
}
