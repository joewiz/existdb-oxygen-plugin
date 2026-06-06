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

import com.existdb.oxygen.client.ExistClient;
import com.existdb.oxygen.client.Uploads;
import com.existdb.oxygen.model.ConnectionProfile;
import com.existdb.oxygen.model.ProfileStore;
import com.existdb.oxygen.project.ExistdbProjectConfig;

import ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace;
import ro.sync.exml.workspace.api.standalone.project.ProjectController;
import ro.sync.exml.workspace.api.standalone.project.ProjectPopupMenuCustomizer;

import java.awt.Frame;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingWorker;

/**
 * Adds an "Upload to eXist…" item to Oxygen's Project-pane contextual menu, uploading the selected
 * file(s)/folder(s) to a collection on a saved eXist-db server. The target server and collection are
 * pre-filled from the nearest {@code .existdb.json} (closest ancestor-or-self, so each repo within a
 * multi-repo project resolves to its own config); when a descriptor is found, each file's path
 * relative to the descriptor's directory is mirrored under the target collection.
 */
public final class ProjectUploadCustomizer implements ProjectPopupMenuCustomizer {

  private final transient StandalonePluginWorkspace workspace;
  private final transient ProfileStore profileStore;

  public ProjectUploadCustomizer(StandalonePluginWorkspace workspace, ProfileStore profileStore) {
    this.workspace = workspace;
    this.profileStore = profileStore;
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
    JMenuItem item = new JMenuItem("Upload to eXist…");
    item.addActionListener(e -> upload(selected));
    menu.addSeparator();
    menu.add(item);
  }

  private void upload(File[] files) {
    List<ConnectionProfile> profiles = profileStore.loadAll();
    if (profiles.isEmpty()) {
      workspace.showInformationMessage("Add an eXist-db connection first (eXist-db pane → gear).");
      return;
    }
    ExistdbProjectConfig config =
        ExistdbProjectConfig.findNearest(files[0], projectRoot()).orElse(null);
    UploadToExistDialog.Result choice =
        UploadToExistDialog.choose(activeFrame(), profiles, files.length, config);
    if (choice == null) {
      return;
    }
    final ExistClient client = new ExistClient(choice.server());
    final String target = choice.targetCollection();
    new SwingWorker<Integer, Void>() {
      @Override
      protected Integer doInBackground() throws Exception {
        int count = 0;
        for (File file : files) {
          String parentPath = parentPathFor(file, config, target);
          ensureCollection(client, parentPath);
          count += Uploads.uploadRecursive(client, parentPath, file);
        }
        return count;
      }

      @Override
      protected void done() {
        try {
          workspace.showStatusMessage("Uploaded " + get() + " file(s) to " + target
              + " on " + choice.server().getName());
        } catch (Exception ex) {
          Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
          workspace.showErrorMessage("Upload failed: " + cause.getMessage());
        }
      }
    }.execute();
  }

  /**
   * The collection a file should be uploaded under: when a descriptor was found, the target plus the
   * file's parent path relative to the descriptor's directory (so {@code repo/modules/foo.xqm} lands
   * in {@code <target>/modules}); otherwise the target collection directly.
   */
  private static String parentPathFor(File file, ExistdbProjectConfig config, String target) {
    if (config == null) {
      return target;
    }
    Path relative = config.descriptorDir().toPath().relativize(file.toPath());
    if (relative.startsWith("..")) {
      return target; // file outside the descriptor's directory — upload flat
    }
    Path relativeParent = relative.getParent();
    if (relativeParent == null) {
      return target;
    }
    return target + "/" + relativeParent.toString().replace(File.separatorChar, '/');
  }

  /**
   * Ensures {@code path} and all its ancestors exist, creating each level top-down. The server's
   * createCollection does not create missing ancestors, so a deep target is built one level at a
   * time; creating an existing level is a no-op.
   */
  private static void ensureCollection(ExistClient client, String path)
      throws java.io.IOException, InterruptedException {
    StringBuilder built = new StringBuilder();
    for (String segment : path.split("/")) {
      if (segment.isEmpty()) {
        continue;
      }
      built.append('/').append(segment);
      client.createCollection(built.toString());
    }
  }

  /** The project's root directory (the {@code .xpr}'s folder), used as the descriptor-walk boundary. */
  private File projectRoot() {
    ProjectController project = workspace.getProjectManager();
    URL projectUrl = project == null ? null : project.getCurrentProjectURL();
    if (projectUrl == null || !"file".equals(projectUrl.getProtocol())) {
      return null;
    }
    try {
      return new File(projectUrl.toURI()).getParentFile();
    } catch (java.net.URISyntaxException e) {
      return null;
    }
  }

  /** The active top-level frame (for dialog ownership), or {@code null} if none can be found. */
  private static Frame activeFrame() {
    Window window = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
    while (window != null && !(window instanceof Frame)) {
      window = window.getOwner();
    }
    return (Frame) window;
  }
}
