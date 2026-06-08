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

import com.existdb.oxygen.model.ConnectionProfile;
import com.existdb.oxygen.model.ProfileStore;
import com.existdb.oxygen.project.BuildConfig;
import com.existdb.oxygen.project.ProjectConnection;

import ro.sync.exml.workspace.api.editor.WSEditor;
import ro.sync.exml.workspace.api.editor.page.text.WSTextEditorPage;
import ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace;
import ro.sync.exml.workspace.api.standalone.project.ProjectController;
import ro.sync.exml.workspace.api.standalone.project.ProjectPopupMenuCustomizer;

import java.awt.Font;
import java.awt.Frame;
import java.awt.GridLayout;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.swing.ImageIcon;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.text.JTextComponent;

/**
 * Adds a "Build" item to Oxygen's Project-pane contextual menu. It finds the package's build root
 * (the closest ancestor-or-self with a {@code .existdb.json} build section or a build marker — Ant,
 * Maven, npm, gulp), runs the build through the user's login shell ({@link BuildRunner}, so toolchain
 * binaries resolve without hardcoded paths), and streams output to the "eXist-db Build" console.
 *
 * <p>Running a project-defined command is gated by a trust prompt (the command and directory are
 * shown; "don't ask again" remembers the directory) — a project file shouldn't silently execute
 * arbitrary commands.</p>
 */
public final class ProjectBuildCustomizer implements ProjectPopupMenuCustomizer {

  private static final ImageIcon MENU_ICON = loadMenuIcon();

  private final transient StandalonePluginWorkspace workspace;
  private final transient ProfileStore profileStore;
  private final transient BuildConsoleView console;
  private final transient String buildViewId;

  public ProjectBuildCustomizer(StandalonePluginWorkspace workspace, ProfileStore profileStore,
      BuildConsoleView console, String buildViewId) {
    this.workspace = workspace;
    this.profileStore = profileStore;
    this.console = console;
    this.buildViewId = buildViewId;
  }

  @Override
  public void customizePopUpMenu(Object popUp) {
    if (!(popUp instanceof JPopupMenu menu)) {
      return;
    }
    File[] selected = workspace.getProjectManager().getSelectedFiles();
    if (selected == null || selected.length == 0) {
      return;
    }
    menu.add(menuItem("Build", () -> build(selected[0], false)));
    menu.add(menuItem("Build & Install", () -> build(selected[0], true)));
  }

  private JMenuItem menuItem(String label, Runnable action) {
    JMenuItem item = new JMenuItem(label);
    if (MENU_ICON != null) {
      item.setIcon(MENU_ICON);
    }
    item.addActionListener(e -> action.run());
    return item;
  }

  private void build(File selected, boolean installAfter) {
    BuildConfig config = BuildConfig.findNearest(selected, projectRoot()).orElse(null);
    if (config == null) {
      workspace.showInformationMessage("No build file found for this selection — add a \"build\" "
          + "section to .existdb.json, or a build.xml, pom.xml, package.json, or gulpfile.js.");
      return;
    }
    InstallTarget target;
    if (installAfter) {
      target = chooseInstallTarget(config);
      if (target == null) {
        return; // no saved connections, or the user cancelled
      }
    } else {
      target = null;
      if (!confirmBuild(config)) {
        return;
      }
    }
    workspace.showView(buildViewId, true);
    console.setConsoleFont(currentEditorFont());
    console.clear();
    console.appendLine("$ " + config.command());
    console.appendLine("  (in " + config.dir().getAbsolutePath() + ")");
    console.appendLine("");
    BuildRunner.run(config.command(), config.dir(), console::appendLine,
        exitCode -> onBuildFinished(config, exitCode, target));
  }

  private void onBuildFinished(BuildConfig config, int exitCode, InstallTarget target) {
    console.appendLine("");
    if (exitCode != 0) {
      console.appendLine("BUILD FAILED (exit code " + exitCode + ")");
      workspace.showStatusMessage("Build failed: " + config.dir().getName());
      return;
    }
    console.appendLine("BUILD SUCCESSFUL");
    Optional<File> artifact = config.locateArtifact();
    artifact.ifPresent(file -> console.appendLine("Artifact: " + file.getAbsolutePath()));
    if (target == null) {
      workspace.showStatusMessage("Build succeeded: " + config.dir().getName());
    } else if (artifact.isEmpty()) {
      console.appendLine("No .xar artifact found to install.");
      workspace.showStatusMessage("Build succeeded, but no .xar to install");
    } else {
      install(config.dir(), artifact.get(), target);
    }
  }

  /** Installs the built {@code .xar} on the target server via {@code xst package install}. */
  private void install(File dir, File xar, InstallTarget target) {
    console.appendLine("");
    console.appendLine("$ xst package install " + xar.getName()
        + "  →  " + target.serverRoot() + " (as " + target.user() + ")");
    Map<String, String> env = new HashMap<>();
    env.put("EXISTDB_SERVER", target.serverRoot());
    env.put("EXISTDB_USER", target.user());
    env.put("EXISTDB_PASS", target.password() == null ? "" : target.password());
    if (target.acceptSelfSigned()) {
      // Let xst/node-exist accept eXist's default self-signed HTTPS cert (dev setups).
      env.put("NODE_TLS_REJECT_UNAUTHORIZED", "0");
    }
    String command = "xst package install " + shellQuote(xar.getAbsolutePath());
    BuildRunner.run(command, dir, env, console::appendLine, exitCode -> {
      console.appendLine("");
      if (exitCode == 0) {
        console.appendLine("INSTALL SUCCESSFUL");
        workspace.showStatusMessage("Installed " + xar.getName() + " to " + target.label());
      } else {
        console.appendLine("INSTALL FAILED (exit code " + exitCode + ")");
        workspace.showStatusMessage("Install failed: " + xar.getName());
      }
    });
  }

  /** Where to install: the eXist server root + credentials, and a display label. */
  private record InstallTarget(String serverRoot, String user, String password,
      boolean acceptSelfSigned, String label) {

    static InstallTarget of(ConnectionProfile profile) {
      return new InstallTarget(profile.getServerRoot(), profile.getUser(), profile.getPassword(),
          profile.isAcceptSelfSigned(), profile.getName());
    }
  }

  /**
   * Resolves the install target, installing always through a saved connection (the credential store).
   * A trusted project installs one-click to its resolved connection; otherwise the user confirms and
   * may override the server. Returns {@code null} when there are no connections or the user cancels.
   */
  private InstallTarget chooseInstallTarget(BuildConfig config) {
    List<ConnectionProfile> profiles = profileStore.loadAll();
    if (profiles.isEmpty()) {
      workspace.showInformationMessage("Add an eXist-db connection first (eXist-db pane → gear).");
      return null;
    }
    ConnectionProfile resolved = resolveDefaultProfile(config.dir(), profiles);
    String dirPath = config.dir().getAbsolutePath();
    if (profileStore.isBuildDirTrusted(dirPath)) {
      return InstallTarget.of(resolved); // trusted → one-click to the resolved connection
    }
    String[] names = profiles.stream().map(ConnectionProfile::getName).toArray(String[]::new);
    JComboBox<String> serverCombo = new JComboBox<>(names);
    serverCombo.setSelectedIndex(Math.max(0, profiles.indexOf(resolved)));
    JCheckBox remember = new JCheckBox("Don't ask again for this project");
    JPanel panel = new JPanel(new GridLayout(0, 1, 0, 4));
    panel.add(new JLabel("Build, then install this project?"));
    panel.add(new JLabel(config.command()));
    panel.add(new JLabel(dirPath));
    panel.add(new JLabel("Install to:"));
    panel.add(serverCombo);
    panel.add(remember);
    int choice = JOptionPane.showConfirmDialog(activeFrame(), panel, "Build and Install",
        JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
    if (choice != JOptionPane.OK_OPTION) {
      return null;
    }
    if (remember.isSelected()) {
      profileStore.addTrustedBuildDir(dirPath);
    }
    return InstallTarget.of(profiles.get(serverCombo.getSelectedIndex()));
  }

  /**
   * The connection to install {@code buildRoot} to by default: the server named in the closest
   * {@code .existdb.json} or {@code .env} (see {@link ProjectConnection}) matched to a saved
   * connection, falling back to the default connection.
   */
  private ConnectionProfile resolveDefaultProfile(File buildRoot, List<ConnectionProfile> profiles) {
    ConnectionProfile match = ProjectConnection.resolve(buildRoot, projectRoot())
        .map(resolved -> matchProfile(resolved.serverRoot(), profiles))
        .orElse(null);
    return match != null ? match : defaultProfile(profiles);
  }

  /** The saved connection whose base URL matches {@code serverRoot} (eXist root), or {@code null}. */
  private static ConnectionProfile matchProfile(String serverRoot, List<ConnectionProfile> profiles) {
    String wanted = stripTrailingSlash(serverRoot);
    for (ConnectionProfile profile : profiles) {
      String base = stripTrailingSlash(profile.getBaseUrl());
      if (base != null && (base.startsWith(wanted) || wanted.startsWith(base))) {
        return profile;
      }
    }
    return null;
  }

  private ConnectionProfile defaultProfile(List<ConnectionProfile> profiles) {
    String defaultId = profileStore.defaultProfileId();
    return profiles.stream()
        .filter(p -> p.getId() != null && p.getId().equals(defaultId))
        .findFirst()
        .orElse(profiles.get(0));
  }

  private static String stripTrailingSlash(String url) {
    if (url == null) {
      return null;
    }
    String trimmed = url.trim();
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return trimmed;
  }

  /** Single-quotes a path for the POSIX shell that {@link BuildRunner} runs the command in. */
  private static String shellQuote(String path) {
    return "'" + path.replace("'", "'\\''") + "'";
  }

  /**
   * Returns true if the build may proceed: already-trusted directories run immediately; otherwise the
   * user is shown the exact command and directory and must approve (optionally remembering the choice).
   */
  private boolean confirmBuild(BuildConfig config) {
    String dirPath = config.dir().getAbsolutePath();
    if (profileStore.isBuildDirTrusted(dirPath)) {
      return true;
    }
    JCheckBox remember = new JCheckBox("Don't ask again for this project");
    JPanel panel = new JPanel(new GridLayout(0, 1, 0, 4));
    panel.add(new JLabel("Run this build command?"));
    panel.add(new JLabel(config.command()));
    panel.add(new JLabel(dirPath));
    panel.add(remember);
    int choice = JOptionPane.showConfirmDialog(activeFrame(), panel, "Build",
        JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
    if (choice != JOptionPane.OK_OPTION) {
      return false;
    }
    if (remember.isSelected()) {
      profileStore.addTrustedBuildDir(dirPath);
    }
    return true;
  }

  /** The project's root directory (the {@code .xpr}'s folder), used as the build-root-walk boundary. */
  private File projectRoot() {
    ProjectController project = workspace.getProjectManager();
    URL projectUrl = project == null ? null : project.getCurrentProjectURL();
    if (projectUrl == null || !"file".equals(projectUrl.getProtocol())) {
      return null;
    }
    try {
      return new File(projectUrl.toURI()).getParentFile();
    } catch (URISyntaxException e) {
      return null;
    }
  }

  /**
   * The active editor's text font — Oxygen's effective editor font (default or user-customized) — so
   * the build console matches the editor. {@code null} when no text editor is open (keeps the
   * console's monospaced default). The SDK exposes no editor-font option, so we read it live.
   */
  private Font currentEditorFont() {
    WSEditor editor = workspace.getCurrentEditorAccess(StandalonePluginWorkspace.MAIN_EDITING_AREA);
    if (editor != null && editor.getCurrentPage() instanceof WSTextEditorPage page
        && page.getTextComponent() instanceof JTextComponent component) {
      return component.getFont();
    }
    return null;
  }

  private static ImageIcon loadMenuIcon() {
    URL url = ProjectBuildCustomizer.class.getResource("/images/exist-server.png");
    return url == null ? null : new ImageIcon(url);
  }

  private static Frame activeFrame() {
    Window window = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
    while (window != null && !(window instanceof Frame)) {
      window = window.getOwner();
    }
    return (Frame) window;
  }
}
