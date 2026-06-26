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

import com.existdb.oxygen.client.ExistHttpException;
import com.existdb.oxygen.client.XQueryError;
import java.awt.geom.Rectangle2D;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;

/**
 * Moves the editor caret to the position of a query error, shared by the two run paths (Run Current
 * Editor and Run in Results View). Both submit the editor's selection if there is one, else the whole
 * document, so the server's error coordinates are relative to the submitted text — {@code baseOffset}
 * (the selection's start, or 0) maps them back to the document.
 */
final class QueryErrorCaret {

  private QueryErrorCaret() {
  }

  /**
   * Places the caret at the error position carried by a failed run, if any. No-op when the failure
   * has no position to jump to — a connection error, a non-query error, or a {@code parse-xml}
   * content error whose line/column are absent.
   */
  static void jumpTo(JTextComponent component, String query, int baseOffset, Throwable cause) {
    if (!(cause instanceof ExistHttpException http)) {
      return;
    }
    XQueryError error = XQueryError.from(http.getResponseBody());
    if (error == null || error.line() <= 0) {
      return;
    }
    int within = offsetWithin(query, error.line(), error.column());
    if (within < 0) {
      return;
    }
    int offset = Math.max(0, Math.min(baseOffset + within, component.getDocument().getLength()));
    component.setCaretPosition(offset);
    try {
      Rectangle2D view = component.modelToView2D(offset);
      if (view != null) {
        component.scrollRectToVisible(view.getBounds());
      }
    } catch (BadLocationException ignored) {
      // The caret is placed; we just couldn't compute the view rectangle to scroll it into view.
    }
    component.requestFocusInWindow();
  }

  /**
   * The character offset of a 1-based {@code line}/{@code column} within {@code text} (newlines are
   * {@code \n}, matching both the server's line counting and Swing's document model), or -1 if the
   * line runs past the end of the text.
   */
  static int offsetWithin(String text, int line, int column) {
    int offset = 0;
    for (int l = 1; l < line; l++) {
      int newline = text.indexOf('\n', offset);
      if (newline < 0) {
        return -1;
      }
      offset = newline + 1;
    }
    return offset + Math.max(0, column - 1);
  }
}
