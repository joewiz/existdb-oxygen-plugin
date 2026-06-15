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

import java.awt.Color;
import java.util.Set;

import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/**
 * Lightweight, serialization-method-keyed syntax highlighting for result values — mirroring eXide,
 * which picks a highlighting mode from the chosen method. XML-family output (xml/xhtml/html5, and
 * adaptive when the value is an element) is tokenized as markup; {@code json} as JSON; everything
 * else (text, atomic/adaptive maps) is left plain.
 */
final class ResultHighlighter {

  /** Which highlighter to use for a value, given the active serialization method. */
  enum Lang { XML, JSON, XQUERY, NONE }

  private static final Set<String> JSON_KEYWORDS = Set.of("true", "false", "null");
  // Adaptive serialization emits XQuery-shaped values: map{…}, array{…}, [...], strings, numbers.
  private static final Set<String> XQUERY_KEYWORDS = Set.of("map", "array", "true", "false");

  private static final Color TAG = new Color(0x2A, 0x66, 0xC8);
  private static final Color ATTR_NAME = new Color(0x99, 0x45, 0x00);
  private static final Color STRING = new Color(0x00, 0x80, 0x00);
  private static final Color NUMBER = new Color(0x1C, 0x00, 0xCF);
  private static final Color KEYWORD = new Color(0x0B, 0x36, 0xA8);
  private static final Color PUNCT = new Color(0x70, 0x70, 0x70);
  private static final Color PLAIN = Color.BLACK;

  private final transient StyledDocument doc;
  private final transient String text;
  private int pos;

  private ResultHighlighter(StyledDocument doc, String text) {
    this.doc = doc;
    this.text = text;
  }

  static Lang languageFor(String method, String value) {
    return switch (method) {
      case "json" -> Lang.JSON;
      case "xml", "xhtml", "html5" -> Lang.XML;
      case "adaptive" -> value.stripLeading().startsWith("<") ? Lang.XML : Lang.XQUERY;
      default -> Lang.NONE;
    };
  }

  /** Replaces {@code doc}'s content with {@code value}, colorized for {@code lang}. */
  static void apply(StyledDocument doc, String value, Lang lang) {
    try {
      doc.remove(0, doc.getLength());
    } catch (BadLocationException ignored) {
      // empty document
    }
    ResultHighlighter h = new ResultHighlighter(doc, value);
    try {
      switch (lang) {
        case XML -> h.highlightXml();
        case JSON -> h.highlightCurly(JSON_KEYWORDS);
        case XQUERY -> h.highlightCurly(XQUERY_KEYWORDS);
        default -> h.append(value, PLAIN);
      }
    } catch (BadLocationException e) {
      // Fall back to plain text if tokenizing/inserting ever fails.
      h.safeAppendRemainder();
    }
  }

  private void append(String s, Color color) throws BadLocationException {
    SimpleAttributeSet attrs = new SimpleAttributeSet();
    StyleConstants.setForeground(attrs, color);
    doc.insertString(doc.getLength(), s, attrs);
  }

  private void safeAppendRemainder() {
    try {
      if (pos < text.length()) {
        append(text.substring(pos), PLAIN);
      }
    } catch (BadLocationException ignored) {
      // give up silently
    }
  }

  // ---------------------------------------------------------------------------
  // XML
  // ---------------------------------------------------------------------------

  private void highlightXml() throws BadLocationException {
    while (pos < text.length()) {
      char c = text.charAt(pos);
      if (c == '<') {
        highlightXmlTag();
      } else {
        int start = pos;
        while (pos < text.length() && text.charAt(pos) != '<') {
          pos++;
        }
        append(text.substring(start, pos), PLAIN);
      }
    }
  }

  private void highlightXmlTag() throws BadLocationException {
    int gt = text.indexOf('>', pos);
    if (gt < 0) {
      append(text.substring(pos), PLAIN);
      pos = text.length();
      return;
    }
    String tag = text.substring(pos, gt + 1);
    pos = gt + 1;
    if (tag.startsWith("<!--") || tag.startsWith("<?") || tag.startsWith("<!")) {
      append(tag, PUNCT); // comment / PI / declaration
      return;
    }
    highlightTagInternals(tag);
  }

  private void highlightTagInternals(String tag) throws BadLocationException {
    int i = 0;
    int open = tag.startsWith("</") ? 2 : 1;
    append(tag.substring(0, open), PUNCT); // < or </
    i = open;
    int nameEnd = i;
    while (nameEnd < tag.length() && !isSpace(tag.charAt(nameEnd))
        && tag.charAt(nameEnd) != '>' && tag.charAt(nameEnd) != '/') {
      nameEnd++;
    }
    append(tag.substring(i, nameEnd), TAG); // element name
    i = nameEnd;
    while (i < tag.length()) {
      char c = tag.charAt(i);
      if (c == '"' || c == '\'') {
        int close = tag.indexOf(c, i + 1);
        if (close < 0) {
          close = tag.length() - 1;
        }
        append(tag.substring(i, close + 1), STRING); // attribute value
        i = close + 1;
      } else if (isNameStart(c)) {
        int e = i;
        while (e < tag.length() && (isNameStart(tag.charAt(e)) || tag.charAt(e) == '-'
            || tag.charAt(e) == ':' || Character.isDigit(tag.charAt(e)))) {
          e++;
        }
        append(tag.substring(i, e), ATTR_NAME); // attribute name
        i = e;
      } else {
        append(String.valueOf(c), PUNCT); // = / > whitespace etc.
        i++;
      }
    }
  }

  // ---------------------------------------------------------------------------
  // JSON
  // ---------------------------------------------------------------------------

  /** Scans a curly/JSON-like language (JSON or adaptive XQuery), coloring {@code keywords}. */
  private void highlightCurly(Set<String> keywords) throws BadLocationException {
    while (pos < text.length()) {
      char c = text.charAt(pos);
      if (c == '"') {
        highlightJsonString();
      } else if (Character.isDigit(c) || (c == '-' && pos + 1 < text.length()
          && Character.isDigit(text.charAt(pos + 1)))) {
        highlightJsonNumber();
      } else if (Character.isLetter(c)) {
        highlightWord(keywords);
      } else {
        append(String.valueOf(c), isPunct(c) ? PUNCT : PLAIN);
        pos++;
      }
    }
  }

  private void highlightJsonString() throws BadLocationException {
    int start = pos;
    pos++; // opening quote
    while (pos < text.length()) {
      char c = text.charAt(pos);
      if (c == '\\' && pos + 1 < text.length()) {
        pos += 2;
        continue;
      }
      pos++;
      if (c == '"') {
        break;
      }
    }
    String token = text.substring(start, pos);
    // A string immediately followed by ':' is an object key.
    int look = pos;
    while (look < text.length() && isSpace(text.charAt(look))) {
      look++;
    }
    boolean key = look < text.length() && text.charAt(look) == ':';
    append(token, key ? KEYWORD : STRING);
  }

  private void highlightJsonNumber() throws BadLocationException {
    int start = pos;
    pos++;
    while (pos < text.length() && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '.'
        || text.charAt(pos) == 'e' || text.charAt(pos) == 'E' || text.charAt(pos) == '+'
        || text.charAt(pos) == '-')) {
      pos++;
    }
    append(text.substring(start, pos), NUMBER);
  }

  private void highlightWord(Set<String> keywords) throws BadLocationException {
    int start = pos;
    while (pos < text.length() && Character.isLetter(text.charAt(pos))) {
      pos++;
    }
    String word = text.substring(start, pos);
    append(word, keywords.contains(word) ? KEYWORD : PLAIN);
  }

  private static boolean isSpace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r';
  }

  private static boolean isNameStart(char c) {
    return Character.isLetter(c) || c == '_';
  }

  private static boolean isPunct(char c) {
    return c == '{' || c == '}' || c == '[' || c == ']' || c == ':' || c == ',';
  }
}
