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

import javax.swing.SwingWorker;
import javax.swing.Timer;

/**
 * Reopens the {@code exist://} server editors that were open at the previous shutdown. Oxygen
 * restores {@code file:} tabs from its per-project session state but excludes custom-protocol URLs,
 * so resources opened from the eXist-db pane would otherwise be lost across restarts.
 *
 * <p>Lifecycle: at startup the saved list is reopened once the workbench has settled (the reopen is
 * debounced — each tab Oxygen restores pushes it later, so it fires only after Oxygen's own session
 * restore quiesces, by which point the editor area is ready to accept {@code open()}). The saved
 * list is then kept current as editors open/close, and the authoritative snapshot is taken in the
 * extension's {@code applicationClosing()} (see {@link #persistBeforeClose()}).</p>
 */
public final class ReopenTabsManager {

  /** Debounce window: reopen fires this long after the last startup editor-restore event. */
  private static final int REOPEN_DELAY_MS = 1200;

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
  /** False until the one-time startup reopen has run; gates debouncing vs. live persistence. */
  private transient boolean reopened;
  /** Non-repeating, EDT-fired timer that runs the startup reopen once the restore settles. */
  private transient Timer reopenTimer;

  public ReopenTabsManager(StandalonePluginWorkspace workspace, ProfileStore profileStore) {
    this.workspace = workspace;
    this.profileStore = profileStore;
  }

  /** Schedules the startup reopen and starts tracking open/close to keep the saved list current. */
  public void install() {
    // Snapshot the saved list now: as editors open (Oxygen's restore, then our own reopen) the
    // editorOpened events would otherwise rewrite the option from the still-incomplete live set.
    final List<String> toReopen = profileStore.openTabs();
    if (toReopen.isEmpty()) {
      reopened = true;
    } else {
      reopenTimer = new Timer(REOPEN_DELAY_MS, e -> reopen(toReopen));
      reopenTimer.setRepeats(false);
      reopenTimer.start();
    }
    workspace.addEditorChangeListener(new WSEditorChangeListener() {
      @Override
      public void editorOpened(URL editorLocation) {
        if (reopened) {
          persistUnlessClosing();
        } else if (reopenTimer != null) {
          // Oxygen is still restoring its own tabs — push the reopen until the burst settles.
          reopenTimer.restart();
        }
      }

      @Override
      public void editorClosed(URL editorLocation) {
        if (reopened) {
          persistUnlessClosing();
        }
      }

      @Override
      public void editorRelocated(URL fromLocation, URL toLocation) {
        if (reopened) {
          persistUnlessClosing();
        }
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
    // Mark reopened first: the editorOpened events from our own opens below should persist normally
    // (not restart the debounce timer, which would loop).
    reopened = true;
    // Populate the connection registry so exist:// URLs resolve to their server even if the
    // eXist-db pane hasn't been shown yet (it rebuilds the same registry when first opened).
    ExistContext.setProfiles(profileStore.loadAll(), profileStore.defaultProfileId());
    Set<String> alreadyOpen = existTabLocations();
    List<String> toOpen = new ArrayList<>();
    for (String url : urls) {
      if (!alreadyOpen.contains(url)) {
        toOpen.add(url);
      }
    }
    if (toOpen.isEmpty()) {
      return;
    }
    // Oxygen rejects open() on the AWT/EDT thread (deadlock guard) — open from a separate thread.
    // Note: no persist here. Successful opens fire editorOpened (which persists); a failed open
    // leaves the saved list untouched so the tab can be retried on the next start.
    new SwingWorker<Void, Void>() {
      @Override
      protected Void doInBackground() {
        for (String url : toOpen) {
          reopenOne(url);
        }
        return null;
      }
    }.execute();
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
