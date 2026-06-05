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

import ro.sync.exml.workspace.api.editor.WSEditor;
import ro.sync.exml.workspace.api.editor.page.WSEditorPage;
import ro.sync.exml.workspace.api.editor.page.text.WSTextEditorPage;
import ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace;

import java.awt.event.ActionEvent;
import java.net.URL;

import javax.swing.AbstractAction;
import javax.swing.ImageIcon;
import javax.swing.text.JTextComponent;

/**
 * "Run in Results View": executes the active editor's XQuery (or its selection) and shows the
 * paginated {@link ExistResultsView} (the opt-in eXide-style second results mode). Like Run Current
 * Editor, this sets no context item — the editor content is the query.
 */
public final class RunInResultsViewAction extends AbstractAction {

  private final transient StandalonePluginWorkspace workspace;
  private final transient ExistResultsView resultsView;
  private final transient String viewId;

  public RunInResultsViewAction(StandalonePluginWorkspace workspace, ExistResultsView resultsView,
      String viewId) {
    super("Run in Results View (eXist)");
    this.workspace = workspace;
    this.resultsView = resultsView;
    this.viewId = viewId;
    putValue(SHORT_DESCRIPTION, "Run the current editor's XQuery and show paginated results");
    URL icon = RunInResultsViewAction.class.getResource("/images/run-query.png");
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
    ExistClient client = ExistContext.clientFor(editor.getEditorLocation());
    if (client == null) {
      workspace.showInformationMessage("Connect to eXist-db first (eXist-db view → Connect…).");
      return;
    }
    String query = queryText((JTextComponent) page.getTextComponent());
    if (query.isBlank()) {
      workspace.showInformationMessage("Nothing to run — the editor is empty.");
      return;
    }
    String moduleLoadPath = LangServiceSupport.moduleLoadPath(editor.getEditorLocation());
    String serverId = ExistContext.serverIdFor(editor.getEditorLocation());
    workspace.showView(viewId, true);
    resultsView.run(client, serverId, query, moduleLoadPath, null);
  }

  private static String queryText(JTextComponent component) {
    String selection = component.getSelectedText();
    return selection != null && !selection.isBlank() ? selection : component.getText();
  }

  private static WSTextEditorPage textPage(WSEditor editor) {
    if (editor == null) {
      return null;
    }
    WSEditorPage current = editor.getCurrentPage();
    return current instanceof WSTextEditorPage textPage ? textPage : null;
  }
}
