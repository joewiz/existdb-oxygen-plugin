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

import com.existdb.oxygen.ExistContext;
import com.existdb.oxygen.client.ExistClient;
import com.existdb.oxygen.client.Uploads;
import com.existdb.oxygen.model.ConnectionProfile;
import com.existdb.oxygen.model.ProfileStore;
import com.existdb.oxygen.project.ExistdbProjectConfig;

import ro.sync.exml.workspace.api.editor.WSEditor;
import ro.sync.exml.workspace.api.listeners.WSEditorChangeListener;
import ro.sync.exml.workspace.api.listeners.WSEditorListener;
import ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;

import javax.swing.SwingWorker;

/**
 * Auto-uploads a saved filesystem resource to its mapped eXist-db collection — the Phase 2 upload-on-
 * save. It activates only for projects that opt in via the nearest {@code .existdb.json}'s
 * {@code "sync": { "onSave": true }} (a safety gate against surprise network writes on every save).
 * When enabled, on save it skips files matching {@code sync.ignore} globs, matches the descriptor's
 * server to a saved connection, and uploads the file to {@code sync.root} + its path relative to the
 * descriptor directory, then refreshes the eXist-db pane.
 */
public final class UploadOnSaveWatcher {

  private final transient StandalonePluginWorkspace workspace;
  private final transient ProfileStore profileStore;

  public UploadOnSaveWatcher(StandalonePluginWorkspace workspace, ProfileStore profileStore) {
    this.workspace = workspace;
    this.profileStore = profileStore;
  }

  /** Attaches a save listener to each local (file:) editor as it opens. */
  public void install() {
    workspace.addEditorChangeListener(new WSEditorChangeListener() {
      @Override
      public void editorOpened(URL editorLocation) {
        watch(editorLocation);
      }
    }, StandalonePluginWorkspace.MAIN_EDITING_AREA);
  }

  private void watch(URL editorLocation) {
    if (editorLocation == null || !"file".equals(editorLocation.getProtocol())) {
      return;
    }
    WSEditor editor =
        workspace.getEditorAccess(editorLocation, StandalonePluginWorkspace.MAIN_EDITING_AREA);
    if (editor != null) {
      editor.addEditorListener(new WSEditorListener() {
        @Override
        public void editorSaved(int operationType) {
          onSaved(editorLocation);
        }
      });
    }
  }

  private void onSaved(URL editorLocation) {
    File file = toFile(editorLocation);
    if (file == null) {
      return;
    }
    ExistdbProjectConfig config = ExistdbProjectConfig.findNearest(file, null).orElse(null);
    if (config == null || !config.uploadOnSave()
        || config.targetCollection() == null || config.serverUrl() == null) {
      return;
    }
    Path relative = config.descriptorDir().toPath().relativize(file.toPath());
    if (relative.startsWith("..") || isIgnored(relative, config.ignore())) {
      return;
    }
    ConnectionProfile profile = matchProfile(config.serverUrl());
    if (profile == null) {
      return; // no saved connection matches the descriptor's server — nothing to upload to
    }
    final ExistClient client = new ExistClient(profile);
    final String parentPath =
        ProjectUploadCustomizer.parentPathFor(file, config, config.targetCollection());
    new SwingWorker<Void, Void>() {
      @Override
      protected Void doInBackground() throws Exception {
        Uploads.ensureCollection(client, parentPath);
        Uploads.uploadRecursive(client, parentPath, file, true);
        return null;
      }

      @Override
      protected void done() {
        try {
          get();
          workspace.showStatusMessage("Uploaded " + file.getName() + " to " + parentPath
              + " on " + profile.getName());
          ExistContext.fireCollectionChanged(profile.getId(), parentPath);
        } catch (Exception ex) {
          Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
          workspace.showErrorMessage("Upload on save failed: " + cause.getMessage());
        }
      }
    }.execute();
  }

  /** The saved server profile whose base URL matches the descriptor's server URL, or {@code null}. */
  private ConnectionProfile matchProfile(String serverUrl) {
    String wanted = stripTrailingSlash(serverUrl);
    for (ConnectionProfile profile : profileStore.loadAll()) {
      String base = stripTrailingSlash(profile.getBaseUrl());
      if (base != null && (base.startsWith(wanted) || wanted.startsWith(base))) {
        return profile;
      }
    }
    return null;
  }

  /** Whether {@code relative} (the file's path under the descriptor dir) matches any ignore glob. */
  private static boolean isIgnored(Path relative, List<String> globs) {
    Path normalized = Path.of(relative.toString().replace(File.separatorChar, '/'));
    for (String glob : globs) {
      if (FileSystems.getDefault().getPathMatcher("glob:" + glob).matches(normalized)) {
        return true;
      }
    }
    return false;
  }

  private static File toFile(URL url) {
    try {
      return new File(url.toURI());
    } catch (URISyntaxException | IllegalArgumentException e) {
      return null;
    }
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
}
