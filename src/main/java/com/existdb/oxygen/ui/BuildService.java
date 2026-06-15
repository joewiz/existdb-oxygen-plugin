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
import ro.sync.exml.workspace.api.standalone.ui.OKCancelDialog;
import ro.sync.exml.workspace.api.standalone.ui.OxygenUIComponentsFactory;

import java.awt.BorderLayout;
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

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.text.JTextComponent;

/**
 * The shared execution core for building (and optionally installing) an eXist-db package: it runs the
 * resolved {@link BuildConfig}'s command through the user's login shell ({@link BuildRunner}), streams
 * output to the "eXist-db Build" console, and on success installs the produced {@code .xar} via
 * {@code xst package install}. Both the Project-pane "Build"/"Build &amp; Install" menu
 * ({@link ProjectBuildCustomizer}) and the auto-build-on-save watcher ({@link AutoBuildWatcher}) drive
 * builds through this one place so the run, install, target-resolution, and trust logic stay in sync.
 */
public final class BuildService {

  private final transient StandalonePluginWorkspace workspace;
  private final transient ProfileStore profileStore;
  private final transient BuildConsoleView console;
  private final transient String buildViewId;

  public BuildService(StandalonePluginWorkspace workspace, ProfileStore profileStore,
      BuildConsoleView console, String buildViewId) {
    this.workspace = workspace;
    this.profileStore = profileStore;
    this.console = console;
    this.buildViewId = buildViewId;
  }

  /** Where to install: the eXist server root + credentials, and a display label. */
  public record InstallTarget(String serverRoot, String user, String password,
      boolean acceptSelfSigned, String label) {

    public static InstallTarget of(ConnectionProfile profile) {
      return new InstallTarget(profile.getServerRoot(), profile.getUser(), profile.getPassword(),
          profile.isAcceptSelfSigned(), profile.getName());
    }
  }

  /**
   * Runs {@code config}'s build, streaming to the console; on success, installs the built {@code .xar}
   * to {@code target} when it is non-null. {@code reveal} surfaces the console with focus (manual
   * builds) vs. without (background auto-builds). {@code onComplete} runs on the EDT when the whole
   * sequence (build, then install if any) has finished, regardless of outcome.
   */
  public void build(BuildConfig config, InstallTarget target, boolean reveal, Runnable onComplete) {
    workspace.showView(buildViewId, reveal);
    console.setConsoleFont(currentEditorFont());
    console.clear();
    console.appendLine("$ " + config.command());
    console.appendLine("  (in " + config.dir().getAbsolutePath() + ")");
    console.appendLine("");
    BuildRunner.run(config.command(), config.dir(), console::appendLine,
        exitCode -> afterBuild(config, exitCode, target, onComplete));
  }

  private void afterBuild(BuildConfig config, int exitCode, InstallTarget target,
      Runnable onComplete) {
    console.appendLine("");
    if (exitCode != 0) {
      console.appendLine("BUILD FAILED (exit code " + exitCode + ")");
      workspace.showStatusMessage("Build failed: " + config.dir().getName());
      onComplete.run();
      return;
    }
    console.appendLine("BUILD SUCCESSFUL");
    Optional<File> artifact = config.locateArtifact();
    artifact.ifPresent(file -> console.appendLine("Artifact: " + file.getAbsolutePath()));
    if (target == null) {
      workspace.showStatusMessage("Build succeeded: " + config.dir().getName());
      onComplete.run();
    } else if (artifact.isEmpty()) {
      console.appendLine("No .xar artifact found to install.");
      workspace.showStatusMessage("Build succeeded, but no .xar to install");
      onComplete.run();
    } else {
      install(config.dir(), artifact.get(), target, onComplete);
    }
  }

  /** Installs the built {@code .xar} on the target server via {@code xst package install}. */
  private void install(File dir, File xar, InstallTarget target, Runnable onComplete) {
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
      onComplete.run();
    });
  }

  /**
   * The non-interactive install target for auto-build: the connection resolved from the package's
   * {@code .existdb.json}/{@code .env}, else the default connection. {@code null} if no connections
   * are saved.
   */
  public InstallTarget resolveTarget(BuildConfig config) {
    List<ConnectionProfile> profiles = profileStore.loadAll();
    if (profiles.isEmpty()) {
      return null;
    }
    return InstallTarget.of(resolveDefaultProfile(config.dir(), profiles));
  }

  /**
   * The connection to install {@code buildRoot} to by default: the server named in the closest
   * {@code .existdb.json} or {@code .env} (see {@link ProjectConnection}) matched to a saved
   * connection, falling back to the default connection.
   */
  public ConnectionProfile resolveDefaultProfile(File buildRoot, List<ConnectionProfile> profiles) {
    ConnectionProfile match = ProjectConnection.resolve(buildRoot, projectRoot())
        .map(resolved -> matchProfile(resolved.serverRoot(), profiles))
        .orElse(null);
    return match != null ? match : defaultProfile(profiles);
  }

  /**
   * Ensures the package may auto-build: trusted directories proceed silently; otherwise the user is
   * asked once to enable auto-build for this project (the command and directory are shown), and on OK
   * the directory is remembered so later saves run without prompting. Returns whether to proceed.
   */
  public boolean ensureAutoBuildTrusted(BuildConfig config) {
    String dirPath = config.dir().getAbsolutePath();
    if (profileStore.isBuildDirTrusted(dirPath)) {
      return true;
    }
    JPanel panel = new JPanel(new GridLayout(0, 1, 0, 4));
    panel.add(new JLabel("Enable auto-build on save for this project?"));
    panel.add(new JLabel("It will run on each save: " + config.command()));
    panel.add(new JLabel(dirPath));
    if (!showConfirm("Enable Auto-Build", panel)) {
      return false;
    }
    profileStore.addTrustedBuildDir(dirPath);
    return true;
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

  /** The project's root directory (the {@code .xpr}'s folder), used as the build-root-walk boundary. */
  public File projectRoot() {
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

  /** Shows {@code content} in a native Oxygen OK/Cancel dialog; returns true when OK was pressed. */
  public boolean showConfirm(String title, JComponent content) {
    OKCancelDialog dialog = OxygenUIComponentsFactory.createOkCancelDialog(activeFrame(), title, true);
    dialog.getContentPane().add(content, BorderLayout.CENTER);
    dialog.pack();
    dialog.setLocationRelativeTo(activeFrame());
    dialog.setVisible(true);
    return dialog.getResult() == OKCancelDialog.RESULT_OK;
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

  private static Frame activeFrame() {
    Window window = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
    while (window != null && !(window instanceof Frame)) {
      window = window.getOwner();
    }
    return (Frame) window;
  }
}
