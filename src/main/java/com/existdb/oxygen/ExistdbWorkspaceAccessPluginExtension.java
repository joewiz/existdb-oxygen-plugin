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
package com.existdb.oxygen;

import com.existdb.oxygen.model.ProfileStore;
import com.existdb.oxygen.ui.ExistdbBrowserPanel;
import com.existdb.oxygen.ui.GoToDefinitionAction;
import com.existdb.oxygen.ui.QueryDialog;

import ro.sync.ecss.extensions.api.AuthorAccess;
import ro.sync.exml.plugin.workspace.WorkspaceAccessPluginExtension;
import ro.sync.exml.workspace.api.editor.page.text.WSTextEditorPage;
import ro.sync.exml.workspace.api.standalone.MenuBarCustomizer;
import ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace;
import ro.sync.exml.workspace.api.standalone.ViewComponentCustomizer;
import ro.sync.exml.workspace.api.standalone.ViewInfo;
import ro.sync.exml.workspace.api.standalone.actions.MenusAndToolbarsContributorCustomizer;

import java.awt.Frame;
import java.awt.event.ActionEvent;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JPopupMenu;

/**
 * Wires the plugin into the Oxygen workspace: contributes the eXist-db collection view, the
 * "eXist-db" main menu (Run XQuery…, Go to Definition), and the editor contextual-menu actions.
 */
public final class ExistdbWorkspaceAccessPluginExtension implements WorkspaceAccessPluginExtension {

  /** Must match the view id declared in plugin.xml. */
  private static final String VIEW_ID = "ExistdbBrowserViewID";

  @Override
  public void applicationStarted(final StandalonePluginWorkspace pluginWorkspace) {
    final ProfileStore profileStore = new ProfileStore(pluginWorkspace);

    pluginWorkspace.addViewComponentCustomizer(new ViewComponentCustomizer() {
      @Override
      public void customizeView(ViewInfo viewInfo) {
        if (VIEW_ID.equals(viewInfo.getViewID())) {
          viewInfo.setComponent(new ExistdbBrowserPanel(pluginWorkspace, profileStore));
          viewInfo.setTitle("eXist-db");
        }
      }
    });

    final Action runQueryAction = new AbstractAction("Run XQuery…") {
      @Override
      public void actionPerformed(ActionEvent e) {
        new QueryDialog((Frame) pluginWorkspace.getParentFrame()).setVisible(true);
      }
    };
    final Action goToDefinitionAction = new GoToDefinitionAction(pluginWorkspace);

    pluginWorkspace.addMenuBarCustomizer(new MenuBarCustomizer() {
      @Override
      public void customizeMainMenu(JMenuBar mainMenuBar) {
        JMenu menu = new JMenu("eXist-db");
        menu.add(runQueryAction);
        menu.add(goToDefinitionAction);
        // Insert before the trailing Help menu.
        mainMenuBar.add(menu, Math.max(0, mainMenuBar.getMenuCount() - 1));
      }
    });

    // Offer "Go to Definition (eXist)" in the Text-mode editor's contextual menu.
    pluginWorkspace.addMenusAndToolbarsContributorCustomizer(
        new MenusAndToolbarsContributorCustomizer() {
          @Override
          public void customizeTextPopUpMenu(JPopupMenu popUp, WSTextEditorPage textPage) {
            popUp.addSeparator();
            popUp.add(goToDefinitionAction);
          }

          @Override
          public void customizeAuthorPopUpMenu(JPopupMenu popUp, AuthorAccess authorAccess) {
            // No Author-mode contribution.
          }
        });
  }

  @Override
  public boolean applicationClosing() {
    return true;
  }
}
