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

import com.existdb.oxygen.model.ProfileStore;
import com.existdb.oxygen.project.BuildConfig;

import ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace;
import ro.sync.exml.workspace.api.standalone.project.ProjectController;
import ro.sync.exml.workspace.api.standalone.project.ProjectPopupMenuCustomizer;

import java.awt.Frame;
import java.awt.GridLayout;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Optional;

import javax.swing.ImageIcon;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;

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
    JMenuItem item = new JMenuItem("Build");
    if (MENU_ICON != null) {
      item.setIcon(MENU_ICON);
    }
    item.addActionListener(e -> build(selected[0]));
    menu.addSeparator();
    menu.add(item);
  }

  private void build(File selected) {
    BuildConfig config = BuildConfig.findNearest(selected, projectRoot()).orElse(null);
    if (config == null) {
      workspace.showInformationMessage("No build file found for this selection — add a \"build\" "
          + "section to .existdb.json, or a build.xml, pom.xml, package.json, or gulpfile.js.");
      return;
    }
    if (!confirmTrusted(config)) {
      return;
    }
    workspace.showView(buildViewId, true);
    console.clear();
    console.appendLine("$ " + config.command());
    console.appendLine("  (in " + config.dir().getAbsolutePath() + ")");
    console.appendLine("");
    BuildRunner.run(config.command(), config.dir(), console::appendLine,
        exitCode -> onBuildFinished(config, exitCode));
  }

  private void onBuildFinished(BuildConfig config, int exitCode) {
    console.appendLine("");
    if (exitCode == 0) {
      console.appendLine("BUILD SUCCESSFUL");
      Optional<File> artifact = config.locateArtifact();
      if (artifact.isPresent()) {
        console.appendLine("Artifact: " + artifact.get().getAbsolutePath());
      }
      workspace.showStatusMessage("Build succeeded: " + config.dir().getName());
    } else {
      console.appendLine("BUILD FAILED (exit code " + exitCode + ")");
      workspace.showStatusMessage("Build failed: " + config.dir().getName());
    }
  }

  /**
   * Returns true if the build may proceed: already-trusted directories run immediately; otherwise the
   * user is shown the exact command and directory and must approve (optionally remembering the choice).
   */
  private boolean confirmTrusted(BuildConfig config) {
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
    int choice = JOptionPane.showConfirmDialog(activeFrame(), panel, "Run build command?",
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
