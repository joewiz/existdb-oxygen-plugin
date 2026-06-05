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
import com.existdb.oxygen.protocol.ExistURLStreamHandler;

import ro.sync.exml.workspace.api.editor.WSEditor;
import ro.sync.exml.workspace.api.editor.page.WSEditorPage;
import ro.sync.exml.workspace.api.editor.page.text.WSTextEditorPage;
import ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace;

import java.awt.event.ActionEvent;
import java.net.URL;

import javax.swing.AbstractAction;
import javax.swing.SwingWorker;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;

/**
 * Jumps from the symbol under the caret to its definition, via
 * {@code /api/langservice/definition}. The XQuery text plus the 0-based caret line/column are sent
 * to eXist; the returned DB path is opened (through the {@code exist:} scheme) with the caret placed
 * at the definition. Works for any XQuery editor backed by an active eXist connection.
 */
public final class GoToDefinitionAction extends AbstractAction {

  private final transient StandalonePluginWorkspace workspace;

  public GoToDefinitionAction(StandalonePluginWorkspace workspace) {
    super("Go to Definition (eXist-db)");
    this.workspace = workspace;
  }

  @Override
  public void actionPerformed(ActionEvent event) {
    WSEditor editor = workspace.getCurrentEditorAccess(StandalonePluginWorkspace.MAIN_EDITING_AREA);
    WSTextEditorPage page = textPage(editor);
    if (page == null) {
      return;
    }
    final URL location = editor.getEditorLocation();
    final ExistClient client = ExistContext.clientFor(location);
    if (client == null) {
      workspace.showInformationMessage("Connect to eXist-db first (eXist-db view → Connect…).");
      return;
    }
    // The definition resolves on the same server as the current editor.
    final String serverId =
        location == null ? "" : LangServiceSupport.serverId(location.toExternalForm());
    JTextComponent component = (JTextComponent) page.getTextComponent();
    final String query = component.getText();
    final String moduleLoadPath = LangServiceSupport.moduleLoadPath(location);
    final int line;
    final int column;
    try {
      int caret = component.getCaretPosition();
      // Oxygen positions are 1-based; the language service expects 0-based.
      line = page.getLineOfOffset(caret) - 1;
      column = page.getColumnOfOffset(caret) - 1;
    } catch (BadLocationException e) {
      return;
    }

    new SwingWorker<ExistClient.Definition, Void>() {
      @Override
      protected ExistClient.Definition doInBackground() throws Exception {
        return client.definition(query, line, column, moduleLoadPath);
      }

      @Override
      protected void done() {
        try {
          ExistClient.Definition def = get();
          if (def == null) {
            workspace.showInformationMessage("No definition found for the symbol under the caret.");
            return;
          }
          openAt(def, serverId);
        } catch (Exception e) {
          workspace.showErrorMessage("Go to definition failed: " + e.getMessage());
        }
      }
    }.execute();
  }

  private void openAt(ExistClient.Definition def, String serverId) throws Exception {
    URL target = def.uri() != null && !def.uri().isEmpty()
        ? ExistURLStreamHandler.toUrl(serverId, def.uri())
        : workspace.getCurrentEditorAccess(StandalonePluginWorkspace.MAIN_EDITING_AREA)
            .getEditorLocation();
    if (!workspace.open(target)) {
      workspace.showErrorMessage("Could not open " + target);
      return;
    }
    WSEditor opened = workspace.getEditorAccess(target, StandalonePluginWorkspace.MAIN_EDITING_AREA);
    WSTextEditorPage page = textPage(opened);
    if (page == null) {
      return;
    }
    try {
      // def.line() is 0-based; getOffsetOfLineStart expects Oxygen's 1-based line.
      int offset = page.getOffsetOfLineStart(def.line() + 1) + def.column();
      ((JTextComponent) page.getTextComponent()).setCaretPosition(offset);
      page.requestFocus();
    } catch (BadLocationException e) {
      // Definition opened; caret positioning is best-effort.
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
