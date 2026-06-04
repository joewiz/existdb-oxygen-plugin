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
import com.existdb.oxygen.query.QueryRunner;

import ro.sync.exml.workspace.api.editor.WSEditor;
import ro.sync.exml.workspace.api.editor.page.WSEditorPage;
import ro.sync.exml.workspace.api.editor.page.text.WSTextEditorPage;
import ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace;

import java.awt.event.ActionEvent;
import java.net.URL;

import javax.swing.AbstractAction;
import javax.swing.ImageIcon;
import javax.swing.SwingWorker;
import javax.swing.text.JTextComponent;

/**
 * "Run Current Editor": executes the active editor's XQuery (or its selection, if any) against the
 * connected eXist server via {@code /api/query} and opens the serialized results in a new editor —
 * no transformation scenario to configure. Available from the eXist-db menu, the editor pop-up, and
 * a toolbar button.
 */
public final class RunCurrentEditorAction extends AbstractAction {

  private final transient StandalonePluginWorkspace workspace;

  public RunCurrentEditorAction(StandalonePluginWorkspace workspace) {
    super("Run Current Editor (eXist)");
    this.workspace = workspace;
    putValue(SHORT_DESCRIPTION, "Run the current editor's XQuery against eXist");
    URL icon = RunCurrentEditorAction.class.getResource("/images/run-query.png");
    if (icon != null) {
      putValue(SMALL_ICON, new ImageIcon(icon));
    }
  }

  @Override
  public void actionPerformed(ActionEvent event) {
    WSEditor editor = workspace.getCurrentEditorAccess(StandalonePluginWorkspace.MAIN_EDITING_AREA);
    WSTextEditorPage page = textPage(editor);
    if (page == null) {
      workspace.showInformationMessage("Open an XQuery editor to run.");
      return;
    }
    // Route to the editor's own server; the default server for an unsaved/local query.
    final ExistClient client = ExistContext.clientFor(editor.getEditorLocation());
    if (client == null) {
      workspace.showInformationMessage("Connect to eXist-db first (eXist-db view → Connect…).");
      return;
    }
    final JTextComponent component = (JTextComponent) page.getTextComponent();
    final String query = queryText(component);
    if (query.isBlank()) {
      workspace.showInformationMessage("Nothing to run — the editor is empty.");
      return;
    }
    final String moduleLoadPath = LangServiceSupport.moduleLoadPath(editor.getEditorLocation());
    workspace.showStatusMessage("Running XQuery against eXist…");

    new SwingWorker<QueryRunner.QueryResult, Void>() {
      @Override
      protected QueryRunner.QueryResult doInBackground() throws Exception {
        return QueryRunner.execute(client, query, moduleLoadPath);
      }

      @Override
      protected void done() {
        try {
          openResults(get());
        } catch (Exception e) {
          Throwable cause = e.getCause() != null ? e.getCause() : e;
          workspace.showErrorMessage("XQuery failed: " + cause.getMessage());
        }
      }
    }.execute();
  }

  /** The current selection if there is one, otherwise the whole editor text. */
  private static String queryText(JTextComponent component) {
    String selection = component.getSelectedText();
    return selection != null && !selection.isBlank() ? selection : component.getText();
  }

  private void openResults(QueryRunner.QueryResult result) {
    if (result.totalItems() == 0) {
      workspace.showStatusMessage("eXist: the query returned no results.");
      return;
    }
    boolean xml = QueryRunner.looksLikeXml(result.output());
    workspace.createNewEditor(xml ? "xml" : "txt", xml ? "text/xml" : "text/plain", result.output());
    if (result.truncated()) {
      workspace.showStatusMessage("eXist: showing the first " + QueryRunner.MAX_ITEMS
          + " of " + result.totalItems() + " items.");
    } else {
      workspace.showStatusMessage("eXist: " + result.totalItems() + " item(s).");
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
