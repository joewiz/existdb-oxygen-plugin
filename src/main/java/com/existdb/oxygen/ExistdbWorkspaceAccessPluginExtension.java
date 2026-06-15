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
import com.existdb.oxygen.ui.BuildConsoleView;
import com.existdb.oxygen.ui.CompletionAction;
import com.existdb.oxygen.ui.EvaluateQueryAction;
import com.existdb.oxygen.ui.ExistResultsView;
import com.existdb.oxygen.ui.ExistdbBrowserPanel;
import com.existdb.oxygen.ui.GoToDefinitionAction;
import com.existdb.oxygen.ui.HoverAction;
import com.existdb.oxygen.ui.ProjectBuildCustomizer;
import com.existdb.oxygen.ui.ProjectUploadCustomizer;
import com.existdb.oxygen.ui.ReopenTabsManager;
import com.existdb.oxygen.ui.RunCurrentEditorAction;
import com.existdb.oxygen.ui.RunInResultsViewAction;
import com.existdb.oxygen.ui.SignatureHelpAction;
import com.existdb.oxygen.ui.UploadOnSaveWatcher;

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

import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.net.URL;
import java.util.Arrays;

import javax.swing.Action;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPopupMenu;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.text.JTextComponent;

/**
 * Wires the plugin into the Oxygen workspace: contributes the eXist-db collection view, the editor
 * contextual-menu actions (Run Current Editor, go-to-definition, completion, hover), and a toolbar
 * button for "Run Current Editor".
 */
public final class ExistdbWorkspaceAccessPluginExtension implements WorkspaceAccessPluginExtension {

  /** Must match the view ids declared in plugin.xml. */
  private static final String VIEW_ID = "ExistdbBrowserViewID";
  private static final String RESULTS_VIEW_ID = "ExistdbResultsViewID";
  private static final String BUILD_VIEW_ID = "ExistdbBuildViewID";
  /** Client-property flag so a text component's selection watcher is attached at most once. */
  private static final String SELECTION_WATCH_KEY = "existdb.selectionWatch";

  /** Kept so {@link #applicationClosing()} can save the open exist:// tabs before Oxygen exits. */
  private transient ReopenTabsManager reopenTabsManager;

  @Override
  public void applicationStarted(final StandalonePluginWorkspace pluginWorkspace) {
    final ProfileStore profileStore = new ProfileStore(pluginWorkspace);
    // Share this instance so the Preferences > Plugins > eXist-db page acts on the same store
    // (its change listeners only reach subscribers registered on this very instance).
    ExistContext.setProfileStore(profileStore);

    // Auto-validate exist: XQuery editors (Problems view) without a manual engine selection.
    new ExistAutoValidator(pluginWorkspace).install();

    // "Upload to eXist-db…" in the Project pane's contextual menu (filesystem → server),
    // also reachable via Cmd/Ctrl+U while a tree (Project pane) is focused.
    final ProjectUploadCustomizer uploadCustomizer =
        new ProjectUploadCustomizer(pluginWorkspace, profileStore);
    pluginWorkspace.getProjectManager().addPopUpMenuCustomizer(uploadCustomizer);
    installUploadShortcut(uploadCustomizer);

    // Upload-on-save: auto-upload a saved file when its .existdb.json opts in (sync.onSave).
    new UploadOnSaveWatcher(pluginWorkspace, profileStore).install();

    // "Build" in the Project pane → run the package's build (Ant/Maven/npm/gulp) via the login shell,
    // streaming to the eXist-db Build console.
    final BuildConsoleView buildConsole = new BuildConsoleView();
    pluginWorkspace.getProjectManager().addPopUpMenuCustomizer(
        new ProjectBuildCustomizer(pluginWorkspace, profileStore, buildConsole, BUILD_VIEW_ID));

    // Keep the exist:// server editors associated with the active project — reopen them on startup
    // and swap them in/out on project switch (Oxygen does this for file: tabs but not custom-protocol
    // ones), riding on Oxygen's "Open last edited files from project" setting.
    reopenTabsManager = new ReopenTabsManager(pluginWorkspace, profileStore);
    reopenTabsManager.install();

    final ExistResultsView resultsView = new ExistResultsView(pluginWorkspace, profileStore);
    final URL viewIcon =
        ExistdbWorkspaceAccessPluginExtension.class.getResource("/images/exist-server.png");

    pluginWorkspace.addViewComponentCustomizer(new ViewComponentCustomizer() {
      @Override
      public void customizeView(ViewInfo viewInfo) {
        if (VIEW_ID.equals(viewInfo.getViewID())) {
          viewInfo.setComponent(new ExistdbBrowserPanel(pluginWorkspace, profileStore));
          viewInfo.setTitle("eXist-db");
          if (viewIcon != null) {
            viewInfo.setIcon(new ImageIcon(viewIcon));
          }
        } else if (RESULTS_VIEW_ID.equals(viewInfo.getViewID())) {
          viewInfo.setComponent(resultsView);
          viewInfo.setTitle("eXist-db Results");
          if (viewIcon != null) {
            viewInfo.setIcon(new ImageIcon(viewIcon));
          }
        } else if (BUILD_VIEW_ID.equals(viewInfo.getViewID())) {
          viewInfo.setComponent(buildConsole);
          viewInfo.setTitle("eXist-db Build");
          if (viewIcon != null) {
            viewInfo.setIcon(new ImageIcon(viewIcon));
          }
        }
      }
    });

    final RunCurrentEditorAction runCurrentEditorAction =
        new RunCurrentEditorAction(pluginWorkspace);
    final RunInResultsViewAction runInResultsViewAction =
        new RunInResultsViewAction(pluginWorkspace, resultsView, RESULTS_VIEW_ID);
    // The single user-facing "evaluate" action routes results to the destination chosen in
    // Configure eXist-db Connections (Browse Query Results / Save Query Results to New Editor).
    final EvaluateQueryAction evaluateQueryAction = new EvaluateQueryAction(
        pluginWorkspace, profileStore, runInResultsViewAction, runCurrentEditorAction);
    final Action goToDefinitionAction = new GoToDefinitionAction(pluginWorkspace);
    final Action completionAction = new CompletionAction(pluginWorkspace);
    final Action hoverAction = new HoverAction(pluginWorkspace);
    final SignatureHelpAction signatureHelpAction = new SignatureHelpAction(pluginWorkspace);

    // Wire editor shortcuts onto each text page as it opens / switches to Text mode (the menu was
    // removed, so accelerators are bound on the editor component): Cmd/Ctrl+Enter evaluates the
    // query; Ctrl+Space and Cmd/Ctrl+Alt+Slash trigger eXist-aware completion.
    final KeyStroke runShortcut = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,
        Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
    final KeyStroke completionShortcut =
        KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK);
    final KeyStroke completionShortcutAlt = KeyStroke.getKeyStroke(KeyEvent.VK_SLASH,
        Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | InputEvent.ALT_DOWN_MASK);
    // Cmd/Ctrl+Shift+Space re-summons parameter hints (they also pop automatically while typing).
    final KeyStroke signatureShortcut = KeyStroke.getKeyStroke(KeyEvent.VK_SPACE,
        Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | InputEvent.SHIFT_DOWN_MASK);
    // Surface each action's shortcut in the contextual menu (the real key handling is the editor
    // input-map bindings / F1 dispatcher below; these accelerators are for display). Completion
    // advertises the reliable Cmd/Ctrl+Alt+Slash — Ctrl+Space is intercepted by macOS.
    evaluateQueryAction.putValue(Action.ACCELERATOR_KEY, runShortcut);
    completionAction.putValue(Action.ACCELERATOR_KEY, completionShortcutAlt);
    signatureHelpAction.putValue(Action.ACCELERATOR_KEY, signatureShortcut);
    hoverAction.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0));
    installFunctionDocF1(pluginWorkspace, hoverAction);
    pluginWorkspace.addEditorChangeListener(new WSEditorChangeListener() {
      @Override
      public void editorOpened(URL editorLocation) {
        bindShortcuts(pluginWorkspace, editorLocation);
        watchSelection(pluginWorkspace, editorLocation, evaluateQueryAction);
        watchSignatureHelp(pluginWorkspace, editorLocation, signatureHelpAction);
        evaluateQueryAction.refreshEnabled();
      }

      @Override
      public void editorPageChanged(URL editorLocation) {
        bindShortcuts(pluginWorkspace, editorLocation);
        watchSelection(pluginWorkspace, editorLocation, evaluateQueryAction);
        watchSignatureHelp(pluginWorkspace, editorLocation, signatureHelpAction);
        evaluateQueryAction.refreshEnabled();
      }

      @Override
      public void editorActivated(URL editorLocation) {
        evaluateQueryAction.refreshEnabled();
      }

      @Override
      public void editorClosed(URL editorLocation) {
        evaluateQueryAction.refreshEnabled();
      }

      private void bindShortcuts(StandalonePluginWorkspace workspace, URL editorLocation) {
        // Scope the eXist run/completion shortcuts to XQuery editors only (exist: or local .xq…),
        // so we don't shadow Ctrl+Space etc. in XML/other editors.
        if (!ExistAutoValidator.isXQuery(editorLocation)) {
          return;
        }
        bindShortcut(workspace, editorLocation, runShortcut, "existRun", evaluateQueryAction);
        bindShortcut(workspace, editorLocation, completionShortcut, "existComplete",
            completionAction);
        bindShortcut(workspace, editorLocation, completionShortcutAlt, "existComplete",
            completionAction);
        bindShortcut(workspace, editorLocation, signatureShortcut, "existSignatureHelp",
            signatureHelpAction);
      }
    }, StandalonePluginWorkspace.MAIN_EDITING_AREA);

    // Offer the eXist editor actions in the Text-mode contextual menu.
    pluginWorkspace.addMenusAndToolbarsContributorCustomizer(
        new MenusAndToolbarsContributorCustomizer() {
          @Override
          public void customizeTextPopUpMenu(JPopupMenu popUp, WSTextEditorPage textPage) {
            evaluateQueryAction.refreshEnabled();
            popUp.addSeparator();
            // Ordered by the typical flow while writing a query: complete a name, fill in its
            // arguments, look up what a symbol is, jump to its definition, then run the query.
            popUp.add(completionAction);
            popUp.add(signatureHelpAction);
            popUp.add(hoverAction);
            popUp.add(goToDefinitionAction);
            popUp.add(evaluateQueryAction);
          }

          @Override
          public void customizeAuthorPopUpMenu(JPopupMenu popUp, AuthorAccess authorAccess) {
            // No Author-mode contribution.
          }
        });

    // A toolbar button for one-click "Evaluate Query with eXist-db".
    pluginWorkspace.addToolbarComponentsCustomizer(new ToolbarComponentsCustomizer() {
      @Override
      public void customizeToolbar(ToolbarInfo toolbarInfo) {
        if (!ToolbarComponentsCustomizer.CUSTOM.equals(toolbarInfo.getToolbarID())) {
          return;
        }
        JButton runButton = new JButton(evaluateQueryAction);
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

  /**
   * Overrides F1 with eXist Hover Documentation while an XQuery editor is focused. Oxygen binds
   * F1 to Help as a global menu accelerator, which a component input-map binding can't reliably
   * beat — a {@link KeyEventDispatcher} sees the key before menu accelerators and consumes it.
   */
  private static void installFunctionDocF1(StandalonePluginWorkspace workspace, Action hoverAction) {
    KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
      if (e.getKeyCode() != KeyEvent.VK_F1 || e.getModifiersEx() != 0) {
        return false;
      }
      WSEditor editor =
          workspace.getCurrentEditorAccess(StandalonePluginWorkspace.MAIN_EDITING_AREA);
      boolean inXQueryEditor = editor != null
          && ExistAutoValidator.isXQuery(editor.getEditorLocation())
          && KeyboardFocusManager.getCurrentKeyboardFocusManager()
              .getFocusOwner() instanceof JTextComponent;
      if (!inXQueryEditor) {
        return false;
      }
      if (e.getID() == KeyEvent.KEY_PRESSED) {
        hoverAction.actionPerformed(
            new ActionEvent(e.getSource(), ActionEvent.ACTION_PERFORMED, "F1"));
      }
      return true; // consume PRESSED and RELEASED so Oxygen's Help doesn't also fire
    });
  }

  /**
   * Binds Cmd/Ctrl+Shift+U to "Upload to eXist-db…" via a global dispatcher, scoped to when a tree
   * (the Project pane) has focus, so it doesn't shadow the shortcut in editors. Cmd/Ctrl+Shift+U is
   * unassigned in Oxygen's default Menu Shortcut Keys. Uploads the selected Project-pane files (a
   * no-op when nothing is selected).
   */
  private static void installUploadShortcut(ProjectUploadCustomizer customizer) {
    final int uploadMask =
        Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | InputEvent.SHIFT_DOWN_MASK;
    KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
      if (e.getKeyCode() != KeyEvent.VK_U || e.getModifiersEx() != uploadMask) {
        return false;
      }
      if (!(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()
          instanceof JTree)) {
        return false;
      }
      if (e.getID() == KeyEvent.KEY_PRESSED) {
        customizer.uploadSelected();
      }
      return true; // consume PRESSED and RELEASED so the keystroke isn't handled twice
    });
  }

  /** Binds {@code shortcut} → {@code action} on a text editor's input map (idempotent). */
  private static void bindShortcut(StandalonePluginWorkspace workspace, URL editorLocation,
      KeyStroke shortcut, String key, Action action) {
    WSEditor editor =
        workspace.getEditorAccess(editorLocation, StandalonePluginWorkspace.MAIN_EDITING_AREA);
    if (editor != null && editor.getCurrentPage() instanceof WSTextEditorPage page
        && page.getTextComponent() instanceof JComponent component) {
      // Bind on both the focused-component map (wins over Oxygen's own editor bindings) and the
      // ancestor map (in case focus is delegated to a child of the text component).
      component.getInputMap(JComponent.WHEN_FOCUSED).put(shortcut, key);
      component.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(shortcut, key);
      component.getActionMap().put(key, action);
    }
  }

  /**
   * Refreshes {@code action}'s enabled state as the selection changes in this editor, so the
   * "Evaluate Query" toolbar button/menu enable when text is selected. Attached once per component.
   */
  private static void watchSelection(StandalonePluginWorkspace workspace, URL editorLocation,
      EvaluateQueryAction action) {
    WSEditor editor =
        workspace.getEditorAccess(editorLocation, StandalonePluginWorkspace.MAIN_EDITING_AREA);
    if (editor != null && editor.getCurrentPage() instanceof WSTextEditorPage page
        && page.getTextComponent() instanceof JTextComponent component
        && component.getClientProperty(SELECTION_WATCH_KEY) == null) {
      component.putClientProperty(SELECTION_WATCH_KEY, Boolean.TRUE);
      component.addCaretListener(e -> action.refreshEnabled());
    }
  }

  /** Attaches the auto parameter-hint trigger to an XQuery editor's text component (idempotent). */
  private static void watchSignatureHelp(StandalonePluginWorkspace workspace, URL editorLocation,
      SignatureHelpAction action) {
    if (!ExistAutoValidator.isXQuery(editorLocation)) {
      return;
    }
    WSEditor editor =
        workspace.getEditorAccess(editorLocation, StandalonePluginWorkspace.MAIN_EDITING_AREA);
    if (editor != null && editor.getCurrentPage() instanceof WSTextEditorPage page
        && page.getTextComponent() instanceof JTextComponent component) {
      action.watch(component);
    }
  }

  @Override
  public boolean applicationClosing() {
    // Snapshot the open exist:// editors now, while they're still open — Oxygen tears them down on
    // close, which would otherwise leave nothing to reopen next session.
    if (reopenTabsManager != null) {
      reopenTabsManager.persistBeforeClose();
    }
    return true;
  }
}
