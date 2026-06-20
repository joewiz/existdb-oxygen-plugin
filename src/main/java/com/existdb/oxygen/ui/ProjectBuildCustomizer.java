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
import com.existdb.oxygen.ui.BuildService.InstallTarget;

import ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace;
import ro.sync.exml.workspace.api.standalone.project.ProjectPopupMenuCustomizer;

import java.awt.GridLayout;
import java.io.File;
import java.net.URL;
import java.util.List;

import javax.swing.ImageIcon;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;

/**
 * Adds "Build" and "Build &amp; Install" items to Oxygen's Project-pane contextual menu. It finds the
 * package's build root (the closest ancestor-or-self with a {@code .existdb.json} build section or a
 * build marker — Ant, Maven, npm, gulp) and runs it through the shared {@link BuildService}, which
 * streams output to the "eXist-db Build" console and (for Build &amp; Install) deploys the built
 * {@code .xar} via {@code xst}.
 *
 * <p>Running a project-defined command is gated by a trust prompt (the command and directory are
 * shown; "don't ask again" remembers the directory) — a project file shouldn't silently execute
 * arbitrary commands.</p>
 */
public final class ProjectBuildCustomizer implements ProjectPopupMenuCustomizer {

  private static final ImageIcon MENU_ICON = loadMenuIcon();

  private final transient StandalonePluginWorkspace workspace;
  private final transient ProfileStore profileStore;
  private final transient BuildService buildService;

  public ProjectBuildCustomizer(StandalonePluginWorkspace workspace, ProfileStore profileStore,
      BuildService buildService) {
    this.workspace = workspace;
    this.profileStore = profileStore;
    this.buildService = buildService;
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
    BuildConfig config = BuildConfig.findNearest(selected, buildService.projectRoot()).orElse(null);
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
    buildService.build(config, target, true, () -> { });
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
    ConnectionProfile resolved = buildService.resolveDefaultProfile(config.dir(), profiles);
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
    if (!buildService.showConfirm("Build and Install", panel)) {
      return null;
    }
    if (remember.isSelected()) {
      profileStore.addTrustedBuildDir(dirPath);
    }
    return InstallTarget.of(profiles.get(serverCombo.getSelectedIndex()));
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
    if (!buildService.showConfirm("Build", panel)) {
      return false;
    }
    if (remember.isSelected()) {
      profileStore.addTrustedBuildDir(dirPath);
    }
    return true;
  }

  private static ImageIcon loadMenuIcon() {
    URL url = ProjectBuildCustomizer.class.getResource("/images/exist-server.png");
    return url == null ? null : new ImageIcon(url);
  }
}
