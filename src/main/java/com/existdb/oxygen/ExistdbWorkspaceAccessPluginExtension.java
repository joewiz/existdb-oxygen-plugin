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
import com.existdb.oxygen.ui.RunCurrentEditorAction;

import ro.sync.ecss.extensions.api.AuthorAccess;
import ro.sync.exml.plugin.workspace.WorkspaceAccessPluginExtension;
import ro.sync.exml.workspace.api.editor.WSEditor;
import ro.sync.exml.workspace.api.editor.page.text.WSTextEditorPage;
import ro.sync.exml.workspace.api.listeners.WSEditorChangeListener;
import ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace;
import ro.sync.exml.workspace.api.standalone.ToolbarComponentsCustomizer;
import ro.sync.exml.workspace.api.standalone.ToolbarInfo;
import ro.sync.exml.workspace.api.standalone.ViewComponentCustomizer;
import ro.sync.exml.workspace.api.standalone.ViewInfo;
import ro.sync.exml.workspace.api.standalone.actions.MenusAndToolbarsContributorCustomizer;

import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.net.URL;
import java.util.Arrays;

import javax.swing.Action;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;

/**
 * Wires the plugin into the Oxygen workspace: contributes the eXist-db collection view, the editor
 * contextual-menu actions (Run Current Editor, go-to-definition, completion, hover), and a toolbar
 * button for "Run Current Editor".
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
          URL viewIcon = ExistdbWorkspaceAccessPluginExtension.class
              .getResource("/images/exist-server.png");
          if (viewIcon != null) {
            viewInfo.setIcon(new ImageIcon(viewIcon));
          }
        }
      }
    });

    final Action runCurrentEditorAction = new RunCurrentEditorAction(pluginWorkspace);
    final Action goToDefinitionAction = new GoToDefinitionAction(pluginWorkspace);
    final Action completionAction = new CompletionAction(pluginWorkspace);
    final Action hoverAction = new HoverAction(pluginWorkspace);

    // Bind Cmd/Ctrl+Enter to Run Current Editor inside text editors (the menu was removed, so the
    // accelerator is wired onto each text page as it opens / switches to Text mode).
    final KeyStroke runShortcut = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,
        Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
    runCurrentEditorAction.putValue(Action.ACCELERATOR_KEY, runShortcut);
    pluginWorkspace.addEditorChangeListener(new WSEditorChangeListener() {
      @Override
      public void editorOpened(URL editorLocation) {
        bindRunShortcut(pluginWorkspace, editorLocation, runShortcut, runCurrentEditorAction);
      }

      @Override
      public void editorPageChanged(URL editorLocation) {
        bindRunShortcut(pluginWorkspace, editorLocation, runShortcut, runCurrentEditorAction);
      }
    }, StandalonePluginWorkspace.MAIN_EDITING_AREA);

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

  /** Binds the Run-Current-Editor shortcut to a text editor's input map (idempotent). */
  private static void bindRunShortcut(StandalonePluginWorkspace workspace, URL editorLocation,
      KeyStroke shortcut, Action action) {
    WSEditor editor =
        workspace.getEditorAccess(editorLocation, StandalonePluginWorkspace.MAIN_EDITING_AREA);
    if (editor != null && editor.getCurrentPage() instanceof WSTextEditorPage page
        && page.getTextComponent() instanceof JComponent component) {
      component.getInputMap(JComponent.WHEN_FOCUSED).put(shortcut, "existRunCurrentEditor");
      component.getActionMap().put("existRunCurrentEditor", action);
    }
  }

  @Override
  public boolean applicationClosing() {
    return true;
  }
}
