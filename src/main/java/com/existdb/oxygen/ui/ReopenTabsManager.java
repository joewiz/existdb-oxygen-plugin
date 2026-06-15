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
import ro.sync.exml.workspace.api.standalone.project.ProjectController;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.swing.SwingWorker;
import javax.swing.Timer;

/**
 * Keeps the open {@code exist://} server editors associated with the active Oxygen project, mirroring
 * how Oxygen handles its own {@code file:} tabs: it restores them on startup and swaps them in and out
 * as the user switches projects ({@code .xpr} files) — but it excludes custom-protocol URLs from that
 * machinery, so resources opened from the eXist-db pane would otherwise be lost. This manager fills
 * that gap for {@code exist://} editors, keyed per project.
 *
 * <p>It rides on Oxygen's <b>Open last edited files from project</b> setting
 * ({@code open.last.edited.files}): when that is off, Oxygen reopens nothing on startup and keeps tabs
 * as-is across project switches, and this manager does the same (it still <em>records</em> the open set
 * per project, so turning the setting back on restores it).</p>
 *
 * <p>Lifecycle: at startup the saved list for the current project is reopened once the workbench has
 * settled (debounced — each tab Oxygen restores pushes it later, so it fires only after Oxygen's own
 * session restore quiesces). On a project change the leaving project's tabs are snapshotted, the open
 * {@code exist://} tabs are closed (prompting to save any unsaved ones), and the entering project's
 * saved tabs are reopened. The set is kept current as editors open/close, and the authoritative
 * shutdown snapshot is taken in the extension's {@code applicationClosing()} (see
 * {@link #persistBeforeClose()}).</p>
 */
public final class ReopenTabsManager {

  /** Debounce window: reopen fires this long after the last startup editor-restore event. */
  private static final int REOPEN_DELAY_MS = 1200;
  /** Oxygen's global "Open last edited files from project" option (default on). */
  private static final String OPEN_LAST_EDITED_FILES = "open.last.edited.files";

  private final transient StandalonePluginWorkspace workspace;
  private final transient ProfileStore profileStore;
  /** Key (project URL external form, or {@code ""} for none) of the project our tabs belong to now. */
  private transient String currentProjectKey = "";
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
  /** True while a project switch is closing/reopening tabs, so the churn isn't persisted mid-swap. */
  private transient boolean swapping;
  /** Non-repeating, EDT-fired timer that runs the startup reopen once the restore settles. */
  private transient Timer reopenTimer;
  /** The current project's exist:// tabs, tracked in memory. Add-only on open: Oxygen fires the
   *  leaving project's editorClosed events before projectChanged, so removing on close would wipe
   *  it; the active project is reconciled to the live set at shutdown. */
  private final transient LinkedHashSet<String> currentTabs = new LinkedHashSet<>();

  public ReopenTabsManager(StandalonePluginWorkspace workspace, ProfileStore profileStore) {
    this.workspace = workspace;
    this.profileStore = profileStore;
  }

  /** Schedules the startup reopen and starts tracking project switches and editor open/close. */
  public void install() {
    ProjectController projects = workspace.getProjectManager();
    currentProjectKey = projectKey(projects.getCurrentProjectURL());
    scheduleStartupReopen();
    workspace.addEditorChangeListener(new WSEditorChangeListener() {
      @Override
      public void editorOpened(URL editorLocation) {
        if (reopened && !swapping) {
          if (ExistURLStreamHandler.PROTOCOL.equals(editorLocation.getProtocol())) {
            currentTabs.add(editorLocation.toExternalForm());
            persistCurrent();
          }
        } else if (!reopened && reopenTimer != null) {
          // Oxygen is still restoring its own tabs — push the reopen until the burst settles.
          reopenTimer.restart();
        }
      }

      @Override
      public void editorClosed(URL editorLocation) {
        // Intentionally not removed here: a project switch closes the leaving project's editors
        // (firing editorClosed) before projectChanged, so removing now would wipe its saved set.
        // Closed tabs are reconciled from the live set at shutdown (see persistBeforeClose).
      }

      @Override
      public void editorRelocated(URL fromLocation, URL toLocation) {
        if (reopened && !swapping
            && ExistURLStreamHandler.PROTOCOL.equals(toLocation.getProtocol())) {
          currentTabs.remove(fromLocation.toExternalForm());
          currentTabs.add(toLocation.toExternalForm());
          persistCurrent();
        }
      }
    }, StandalonePluginWorkspace.MAIN_EDITING_AREA);
    projects.addProjectChangeListener(this::onProjectChanged);
  }

  /** Reopens the current project's saved tabs on startup, unless Oxygen's reopen setting is off. */
  private void scheduleStartupReopen() {
    if (!reopenEnabled()) {
      reopened = true; // honor the user's choice: nothing is reopened
      return;
    }
    List<String> toReopen = profileStore.openTabs(currentProjectKey);
    if (toReopen.isEmpty()) {
      // One-time upgrade migration: seed this project from the legacy single global list, then clear
      // it so it can never seed a second project.
      List<String> legacy = profileStore.openTabs();
      if (!legacy.isEmpty()) {
        toReopen = legacy;
        profileStore.setOpenTabs(currentProjectKey, legacy);
        profileStore.setOpenTabs(new ArrayList<>());
      }
    }
    if (toReopen.isEmpty()) {
      reopened = true;
    } else {
      final List<String> snapshot = toReopen;
      reopenTimer = new Timer(REOPEN_DELAY_MS, e -> reopen(snapshot));
      reopenTimer.setRepeats(false);
      reopenTimer.start();
    }
  }

  /** Whether Oxygen's "Open last edited files from project" is on (so we reopen/swap to match it). */
  private boolean reopenEnabled() {
    return Boolean.parseBoolean(
        workspace.getOptionsStorage().getOption(OPEN_LAST_EDITED_FILES, "true"));
  }

  /**
   * On a project switch: save the leaving project's {@code exist://} tabs, then (when reopening is on)
   * close them and reopen the entering project's saved set. With the setting off, only the active key
   * is updated — tabs are left exactly as they are, matching Oxygen's own behavior.
   */
  private void onProjectChanged(URL fromProject, URL toProject) {
    // A project switch supersedes any still-pending startup reopen.
    if (reopenTimer != null) {
      reopenTimer.stop();
    }
    reopened = true;
    String toKey = projectKey(toProject);
    if (!reopenEnabled()) {
      currentProjectKey = toKey;
      loadCurrentTabs(toKey);
      return;
    }
    // Oxygen has already closed the leaving project's editors by the time this fires (the live set is
    // empty), so save it from the in-memory set, which the close-storm hasn't touched.
    profileStore.setOpenTabs(projectKey(fromProject), new ArrayList<>(currentTabs));
    currentProjectKey = toKey;
    swapToProject(loadCurrentTabs(toKey));
  }

  /** Loads the saved tabs for {@code key} into {@link #currentTabs} and returns them. */
  private List<String> loadCurrentTabs(String key) {
    List<String> saved = profileStore.openTabs(key);
    currentTabs.clear();
    currentTabs.addAll(saved);
    return saved;
  }

  /** Closes the open {@code exist://} tabs (save-prompting) and reopens {@code toOpen} for the project. */
  private void swapToProject(List<String> toOpen) {
    swapping = true;
    // Close on the EDT (a save prompt for a dirty tab is modal); a user who cancels keeps the tab.
    for (URL location : existTabUrls()) {
      workspace.close(location);
    }
    // Populate the registry so exist:// URLs resolve to their server even if the pane isn't shown.
    ExistContext.setProfiles(profileStore.loadAll(), profileStore.defaultProfileId());
    new SwingWorker<Void, Void>() {
      @Override
      protected Void doInBackground() {
        // Oxygen rejects open() on the AWT/EDT thread (deadlock guard) — open from a worker thread.
        for (String url : toOpen) {
          reopenOne(url);
        }
        return null;
      }

      @Override
      protected void done() {
        swapping = false;
        // Reconcile to what actually opened (a failed reopen, or a tab the user declined to close).
        currentTabs.clear();
        currentTabs.addAll(existTabLocations());
        persistCurrent();
      }
    }.execute();
  }

  /**
   * Saves the final set of open {@code exist://} editors at shutdown, under the current project. Must
   * be called from the workspace extension's {@code applicationClosing()} — i.e. while the editors are
   * still open, before Oxygen tears them down — so the snapshot is the real session, not an empty
   * list. After snapshotting it marks the manager closing so the teardown's {@code editorClosed} storm
   * is a no-op.
   */
  public void persistBeforeClose() {
    profileStore.setOpenTabs(currentProjectKey, new ArrayList<>(existTabLocations()));
    closing = true;
  }

  private void persistCurrent() {
    if (!closing) {
      profileStore.setOpenTabs(currentProjectKey, new ArrayList<>(currentTabs));
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

  /** The locations (external form) of all open {@code exist://} editors in the main editing area. */
  private Set<String> existTabLocations() {
    Set<String> out = new LinkedHashSet<>();
    for (URL location : existTabUrls()) {
      out.add(location.toExternalForm());
    }
    return out;
  }

  /** The {@code exist://} editor URLs currently open in the main editing area, in tab order. */
  private List<URL> existTabUrls() {
    List<URL> out = new ArrayList<>();
    URL[] locations = workspace.getAllEditorLocations(StandalonePluginWorkspace.MAIN_EDITING_AREA);
    if (locations != null) {
      for (URL location : locations) {
        if (location != null && ExistURLStreamHandler.PROTOCOL.equals(location.getProtocol())) {
          out.add(location);
        }
      }
    }
    return out;
  }

  /** A stable per-project key: the project URL's external form, or {@code ""} when there is none. */
  private static String projectKey(URL projectUrl) {
    return projectUrl == null ? "" : projectUrl.toExternalForm();
  }
}
