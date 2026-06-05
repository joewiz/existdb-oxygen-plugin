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

import com.existdb.oxygen.ExistContext;
import com.existdb.oxygen.client.ExistClient;
import com.existdb.oxygen.lang.LangServiceSupport;
import com.existdb.oxygen.lang.MarkdownRenderer;

import ro.sync.exml.workspace.api.editor.WSEditor;
import ro.sync.exml.workspace.api.editor.page.WSEditorPage;
import ro.sync.exml.workspace.api.editor.page.text.WSTextEditorPage;
import ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.geom.Rectangle2D;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JEditorPane;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.SwingWorker;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;

/**
 * "Hover Documentation (eXist-db)": shows eXist's documentation for the symbol under the caret
 * (LSP {@code textDocument/hover}) — a function's signature + docs, or a variable's type — via
 * {@code /api/langservice/hover}. A dedicated action (Oxygen owns native hover for XQuery); the
 * result appears in a small popup at the caret.
 */
public final class HoverAction extends AbstractAction {

  private final transient StandalonePluginWorkspace workspace;

  public HoverAction(StandalonePluginWorkspace workspace) {
    super("Hover Documentation (eXist-db)");
    this.workspace = workspace;
  }

  @Override
  public void actionPerformed(ActionEvent event) {
    WSEditor editor = workspace.getCurrentEditorAccess(StandalonePluginWorkspace.MAIN_EDITING_AREA);
    WSTextEditorPage page = textPage(editor);
    if (page == null) {
      return;
    }
    final ExistClient client = ExistContext.clientFor(editor.getEditorLocation());
    if (client == null) {
      workspace.showInformationMessage("Connect to eXist-db first (eXist-db view → Connect…).");
      return;
    }
    final JTextComponent component = (JTextComponent) page.getTextComponent();
    final String text = component.getText();
    final String moduleLoadPath = LangServiceSupport.moduleLoadPath(editor.getEditorLocation());
    final int caret = component.getCaretPosition();
    final int line;
    final int column;
    try {
      // Oxygen positions are 1-based; the language service expects 0-based.
      line = page.getLineOfOffset(caret) - 1;
      column = page.getColumnOfOffset(caret) - 1;
    } catch (BadLocationException e) {
      return;
    }

    new SwingWorker<ExistClient.Hover, Void>() {
      @Override
      protected ExistClient.Hover doInBackground() throws Exception {
        return client.hover(text, line, column, moduleLoadPath);
      }

      @Override
      protected void done() {
        try {
          ExistClient.Hover hover = get();
          if (hover == null) {
            workspace.showStatusMessage("eXist-db: no documentation for the symbol under the caret.");
          } else {
            showHoverPopup(component, caret, hover.contents());
          }
        } catch (Exception e) {
          workspace.showErrorMessage("eXist-db hover documentation failed: " + e.getMessage());
        }
      }
    }.execute();
  }

  private static void showHoverPopup(JTextComponent component, int caret, String contents) {
    JEditorPane pane = new JEditorPane("text/html", MarkdownRenderer.toHtml(contents));
    pane.setEditable(false);
    pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
    pane.setBackground(Color.WHITE);
    pane.setCaretPosition(0);

    JScrollPane scroll = new JScrollPane(pane);
    scroll.setBorder(BorderFactory.createLineBorder(new Color(0xC8, 0xCE, 0xD6)));
    // No horizontal scrollbar: the HTML wraps to the popup's width instead of spilling over; a
    // vertical scrollbar appears only if the (wrapped) content is taller than the cap.
    scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    int lines = contents.split("\n").length + 3; // a little headroom for wrapped long lines
    scroll.setPreferredSize(new Dimension(460, Math.min(260, Math.max(56, lines * 18))));

    JPopupMenu popup = new JPopupMenu();
    popup.setLayout(new BorderLayout());
    popup.setBorder(BorderFactory.createEmptyBorder());
    popup.add(scroll, BorderLayout.CENTER);
    try {
      Rectangle2D r = component.modelToView2D(caret);
      popup.show(component, (int) r.getX(), (int) (r.getY() + r.getHeight()));
    } catch (BadLocationException e) {
      // Cannot place the popup; skip.
    }
  }

  private static WSTextEditorPage textPage(WSEditor editor) {
    if (editor == null) {
      return null;
    }
    WSEditorPage current = editor.getCurrentPage();
    return current instanceof WSTextEditorPage textPage ? textPage : null;
  }
}
