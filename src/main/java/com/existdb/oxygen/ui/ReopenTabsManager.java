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
import com.existdb.oxygen.model.ProfileStore;
import com.existdb.oxygen.protocol.ExistURLStreamHandler;

import ro.sync.exml.workspace.api.listeners.WSEditorChangeListener;
import ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.swing.SwingUtilities;

/**
 * Reopens the {@code exist://} server editors that were open at the previous shutdown. Oxygen
 * restores {@code file:} tabs from its per-project session state but excludes custom-protocol URLs,
 * so resources opened from the eXist-db pane would otherwise be lost across restarts. This tracks
 * the open {@code exist://} editors via an editor-change listener, persists the list to the plugin
 * options on every open/close, and reopens them once at startup — best effort: a tab whose server
 * is unreachable or whose URL is malformed is simply skipped.
 */
public final class ReopenTabsManager {

  private final transient StandalonePluginWorkspace workspace;
  private final transient ProfileStore profileStore;
  /**
   * Set once the application is closing. On shutdown Oxygen closes every editor, firing
   * {@code editorClosed} for each; without this guard the last of those events would snapshot an
   * empty editor list and overwrite the saved set with nothing. {@link #persistBeforeClose()} takes
   * the authoritative snapshot first (while the editors are still open), then sets this so the
   * teardown close-storm is ignored.
   */
  private transient volatile boolean closing;

  public ReopenTabsManager(StandalonePluginWorkspace workspace, ProfileStore profileStore) {
    this.workspace = workspace;
    this.profileStore = profileStore;
  }

  /** Reopens the persisted server tabs, then tracks open/close to keep the saved list current. */
  public void install() {
    // Snapshot the saved list before any reopen: opening an editor fires editorOpened, which would
    // otherwise rewrite the option from the (still-incomplete) live set and drop pending entries.
    // Defer the reopen so the workbench has finished restoring its own (file:) tabs first.
    final List<String> toReopen = profileStore.openTabs();
    SwingUtilities.invokeLater(() -> reopen(toReopen));
    workspace.addEditorChangeListener(new WSEditorChangeListener() {
      @Override
      public void editorOpened(URL editorLocation) {
        persistUnlessClosing();
      }

      @Override
      public void editorClosed(URL editorLocation) {
        persistUnlessClosing();
      }

      @Override
      public void editorRelocated(URL fromLocation, URL toLocation) {
        persistUnlessClosing();
      }
    }, StandalonePluginWorkspace.MAIN_EDITING_AREA);
  }

  /**
   * Saves the final set of open {@code exist://} editors at shutdown. Must be called from the
   * workspace extension's {@code applicationClosing()} — i.e. while the editors are still open,
   * before Oxygen tears them down — so the snapshot is the real session, not an empty list. After
   * snapshotting it marks the manager closing so the teardown's {@code editorClosed} storm is a no-op.
   */
  public void persistBeforeClose() {
    persistOpenTabs();
    closing = true;
  }

  private void persistUnlessClosing() {
    if (!closing) {
      persistOpenTabs();
    }
  }

  private void reopen(List<String> urls) {
    if (urls.isEmpty()) {
      return;
    }
    // Populate the connection registry so exist:// URLs resolve to their server even if the
    // eXist-db pane hasn't been shown yet (it rebuilds the same registry when first opened).
    ExistContext.setProfiles(profileStore.loadAll(), profileStore.defaultProfileId());
    Set<String> alreadyOpen = existTabLocations();
    for (String url : urls) {
      if (!alreadyOpen.contains(url)) {
        reopenOne(url);
      }
    }
    persistOpenTabs();
  }

  private void reopenOne(String url) {
    try {
      // Build the URL with our handler explicitly: the exist: protocol isn't registered with the
      // JVM's global URL factory, so a plain new URL(url) would fail with "unknown protocol".
      workspace.open(new URL(null, url, new ExistURLStreamHandler()));
    } catch (MalformedURLException e) {
      // A malformed persisted location is skipped on restore.
    }
  }

  /** Persists the {@code exist://} editors currently open, preserving tab order. */
  private void persistOpenTabs() {
    profileStore.setOpenTabs(new ArrayList<>(existTabLocations()));
  }

  /** The locations (external form) of all open {@code exist://} editors in the main editing area. */
  private Set<String> existTabLocations() {
    Set<String> out = new LinkedHashSet<>();
    URL[] locations = workspace.getAllEditorLocations(StandalonePluginWorkspace.MAIN_EDITING_AREA);
    if (locations != null) {
      for (URL location : locations) {
        if (location != null && ExistURLStreamHandler.PROTOCOL.equals(location.getProtocol())) {
          out.add(location.toExternalForm());
        }
      }
    }
    return out;
  }
}
