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
import com.existdb.oxygen.ui.CompletionAction;
import com.existdb.oxygen.ui.ExistdbBrowserPanel;
import com.existdb.oxygen.ui.GoToDefinitionAction;
import com.existdb.oxygen.ui.HoverAction;
import com.existdb.oxygen.ui.QueryDialog;
import com.existdb.oxygen.ui.RunCurrentEditorAction;

import ro.sync.ecss.extensions.api.AuthorAccess;
import ro.sync.exml.plugin.workspace.WorkspaceAccessPluginExtension;
import ro.sync.exml.workspace.api.editor.page.text.WSTextEditorPage;
import ro.sync.exml.workspace.api.standalone.MenuBarCustomizer;
import ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace;
import ro.sync.exml.workspace.api.standalone.ToolbarComponentsCustomizer;
import ro.sync.exml.workspace.api.standalone.ToolbarInfo;
import ro.sync.exml.workspace.api.standalone.ViewComponentCustomizer;
import ro.sync.exml.workspace.api.standalone.ViewInfo;
import ro.sync.exml.workspace.api.standalone.actions.MenusAndToolbarsContributorCustomizer;

import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.util.Arrays;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JPopupMenu;

/**
 * Wires the plugin into the Oxygen workspace: contributes the eXist-db collection view, the
 * "eXist-db" main menu, the editor contextual-menu actions, and a toolbar button for
 * "Run Current Editor".
 */
public final class ExistdbWorkspaceAccessPluginExtension implements WorkspaceAccessPluginExtension {

  /** Must match the view id declared in plugin.xml. */
  private static final String VIEW_ID = "ExistdbBrowserViewID";

  @Override
  public void applicationStarted(final StandalonePluginWorkspace pluginWorkspace) {
    final ProfileStore profileStore = new ProfileStore(pluginWorkspace);

    // Auto-validate exist: XQuery editors (Problems view) without a manual engine selection.
    new ExistAutoValidator(pluginWorkspace).install();

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
    final Action runCurrentEditorAction = new RunCurrentEditorAction(pluginWorkspace);
    final Action goToDefinitionAction = new GoToDefinitionAction(pluginWorkspace);
    final Action completionAction = new CompletionAction(pluginWorkspace);
    final Action hoverAction = new HoverAction(pluginWorkspace);

    pluginWorkspace.addMenuBarCustomizer(new MenuBarCustomizer() {
      @Override
      public void customizeMainMenu(JMenuBar mainMenuBar) {
        JMenu menu = new JMenu("eXist-db");
        menu.add(runCurrentEditorAction);
        menu.add(runQueryAction);
        menu.add(goToDefinitionAction);
        menu.add(completionAction);
        menu.add(hoverAction);
        // Insert before the trailing Help menu.
        mainMenuBar.add(menu, Math.max(0, mainMenuBar.getMenuCount() - 1));
      }
    });

    // Offer the eXist editor actions in the Text-mode contextual menu.
    pluginWorkspace.addMenusAndToolbarsContributorCustomizer(
        new MenusAndToolbarsContributorCustomizer() {
          @Override
          public void customizeTextPopUpMenu(JPopupMenu popUp, WSTextEditorPage textPage) {
            popUp.addSeparator();
            popUp.add(runCurrentEditorAction);
            popUp.add(goToDefinitionAction);
            popUp.add(completionAction);
            popUp.add(hoverAction);
          }

          @Override
          public void customizeAuthorPopUpMenu(JPopupMenu popUp, AuthorAccess authorAccess) {
            // No Author-mode contribution.
          }
        });

    // A toolbar button for one-click "Run Current Editor".
    pluginWorkspace.addToolbarComponentsCustomizer(new ToolbarComponentsCustomizer() {
      @Override
      public void customizeToolbar(ToolbarInfo toolbarInfo) {
        if (!ToolbarComponentsCustomizer.CUSTOM.equals(toolbarInfo.getToolbarID())) {
          return;
        }
        JButton runButton = new JButton(runCurrentEditorAction);
        runButton.setHideActionText(true);
        JComponent[] existing = toolbarInfo.getComponents();
        JComponent[] updated = existing == null ? new JComponent[1]
            : Arrays.copyOf(existing, existing.length + 1);
        updated[updated.length - 1] = runButton;
        toolbarInfo.setComponents(updated);
        toolbarInfo.setTitle("eXist-db");
      }
    });
  }

  @Override
  public boolean applicationClosing() {
    return true;
  }
}
