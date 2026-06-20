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

import com.existdb.oxygen.project.BuildConfig;
import com.existdb.oxygen.ui.BuildService.InstallTarget;

import ro.sync.exml.workspace.api.editor.WSEditor;
import ro.sync.exml.workspace.api.listeners.WSEditorChangeListener;
import ro.sync.exml.workspace.api.listeners.WSEditorListener;
import ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import javax.swing.Timer;

/**
 * Auto-builds (and optionally installs) an eXist-db package when a file under it is saved — the Phase 4
 * build-infrastructure feature. It activates only for packages that opt in via the nearest
 * {@code .existdb.json}'s {@code "build": { "onSave": true }} (a safety gate against surprise builds on
 * every save); {@code "install": true} additionally installs the built {@code .xar}. Builds run through
 * the shared {@link BuildService}, streaming to the "eXist-db Build" console.
 *
 * <p>Saves are <b>debounced per build root</b> (a burst of saves coalesces into one build) and builds
 * for a given root never overlap — a save that lands while a build is running queues exactly one
 * rebuild. All bookkeeping runs on the EDT (the save callback and the debounce timer both fire there),
 * so the per-root state needs no synchronization. Running a project-defined command automatically is
 * gated by a one-time trust prompt per project ({@link BuildService#ensureAutoBuildTrusted}).</p>
 */
public final class AutoBuildWatcher {

  /** Debounce window: a build fires this long after the last save in a burst. */
  private static final int DEBOUNCE_MS = 600;

  private final transient StandalonePluginWorkspace workspace;
  private final transient BuildService buildService;
  /** Per-build-root state, keyed by the build directory's absolute path. EDT-confined. */
  private final transient Map<String, RootState> roots = new HashMap<>();

  public AutoBuildWatcher(StandalonePluginWorkspace workspace, BuildService buildService) {
    this.workspace = workspace;
    this.buildService = buildService;
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
    BuildConfig config = BuildConfig.findNearest(file, buildService.projectRoot()).orElse(null);
    if (config == null || !config.autoBuild()) {
      return;
    }
    if (!buildService.ensureAutoBuildTrusted(config)) {
      return;
    }
    InstallTarget target = config.autoInstall() ? buildService.resolveTarget(config) : null;
    schedule(config, target);
  }

  /** Records the latest config/target for this build root and (re)arms its debounce timer. */
  private void schedule(BuildConfig config, InstallTarget target) {
    String key = config.dir().getAbsolutePath();
    RootState state = roots.computeIfAbsent(key, k -> new RootState());
    state.config = config;
    state.target = target;
    if (state.running) {
      state.rerun = true; // a save during a build → exactly one rebuild after it finishes
      return;
    }
    if (state.timer == null) {
      state.timer = new Timer(DEBOUNCE_MS, e -> fire(key));
      state.timer.setRepeats(false);
    }
    state.timer.restart();
  }

  /** The debounce elapsed: start the build, and on completion run a queued rebuild if one is pending. */
  private void fire(String key) {
    RootState state = roots.get(key);
    if (state == null || state.running) {
      return;
    }
    state.running = true;
    workspace.showStatusMessage("Auto-building " + state.config.dir().getName() + "…");
    buildService.build(state.config, state.target, false, () -> {
      state.running = false;
      if (state.rerun) {
        state.rerun = false;
        state.timer.restart();
      }
    });
  }

  private static File toFile(URL url) {
    try {
      return new File(url.toURI());
    } catch (URISyntaxException | IllegalArgumentException e) {
      return null;
    }
  }

  /** Debounce/serialization state for one build root. Mutated only on the EDT. */
  private static final class RootState {
    private transient BuildConfig config;
    private transient InstallTarget target;
    private transient Timer timer;
    private transient boolean running;
    private transient boolean rerun;
  }
}
