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

import com.existdb.oxygen.ExistAutoValidator;
import com.existdb.oxygen.model.ProfileStore;

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
 * "Evaluate Query with eXist-db": runs the active editor's XQuery (or its selection) and routes the
 * results to the destination chosen in <b>Configure eXist-db Connections</b> — either the paginated
 * Results view ("Browse Query Results", the default) or a new editor ("Save Query Results to New
 * Editor"). A thin dispatcher over {@link RunInResultsViewAction} and {@link RunCurrentEditorAction}.
 *
 * <p>Enabled only when running makes sense: the active editor is an XQuery editor, or it has a text
 * selection to run.</p>
 */
public final class EvaluateQueryAction extends AbstractAction {

  private final transient StandalonePluginWorkspace workspace;
  private final transient ProfileStore profileStore;
  private final transient RunInResultsViewAction browseAction;
  private final transient RunCurrentEditorAction editorAction;

  public EvaluateQueryAction(StandalonePluginWorkspace workspace, ProfileStore profileStore,
      RunInResultsViewAction browseAction, RunCurrentEditorAction editorAction) {
    super("Evaluate Query (eXist-db)");
    this.workspace = workspace;
    this.profileStore = profileStore;
    this.browseAction = browseAction;
    this.editorAction = editorAction;
    putValue(SHORT_DESCRIPTION, "Evaluate this query with eXist-db");
    URL icon = EvaluateQueryAction.class.getResource("/images/run-query.png");
    if (icon != null) {
      putValue(SMALL_ICON, new ImageIcon(icon));
    }
    refreshEnabled();
  }

  @Override
  public void actionPerformed(ActionEvent event) {
    if ("editor".equals(profileStore.resultsDestination())) {
      editorAction.actionPerformed(event);
    } else {
      browseAction.actionPerformed(event);
    }
  }

  /** Recomputes the enabled state from the active editor (XQuery type, or a non-empty selection). */
  public void refreshEnabled() {
    setEnabled(computeEnabled());
  }

  private boolean computeEnabled() {
    WSEditor editor = workspace.getCurrentEditorAccess(StandalonePluginWorkspace.MAIN_EDITING_AREA);
    if (editor == null) {
      return false;
    }
    if (ExistAutoValidator.isXQuery(editor.getEditorLocation())) {
      return true;
    }
    WSEditorPage page = editor.getCurrentPage();
    if (page instanceof WSTextEditorPage textPage
        && textPage.getTextComponent() instanceof JTextComponent component) {
      String selection = component.getSelectedText();
      return selection != null && !selection.isBlank();
    }
    return false;
  }
}
