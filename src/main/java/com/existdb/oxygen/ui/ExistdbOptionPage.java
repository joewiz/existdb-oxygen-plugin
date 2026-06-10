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

import ro.sync.exml.plugin.option.OptionPagePluginExtension;
import ro.sync.exml.workspace.api.PluginWorkspace;

import javax.swing.JComponent;

/**
 * The <b>Preferences → Plugins → eXist-db</b> option page: connections + the query/result and
 * browsing defaults, in one native Preferences page (Oxygen keeps Data Sources and their
 * connections in Preferences too). The UI lives in {@link ExistdbPreferencesPanel}. The eXist-db
 * pane's gear deep-links here via {@code showPreferencesPages(new String[]{KEY}, KEY, true)}.
 */
public final class ExistdbOptionPage extends OptionPagePluginExtension {

  /** The option-page key — used both as Oxygen's page id and to deep-link here from the gear. */
  public static final String KEY = "exist-db";

  private transient ExistdbPreferencesPanel panel;

  @Override
  public JComponent init(PluginWorkspace pluginWorkspace) {
    // Reuse the plugin's shared store so saves notify the live pane/results view; fall back to a
    // fresh store if Preferences is somehow opened before the plugin has started.
    ProfileStore store = ExistContext.profileStore();
    if (store == null) {
      store = new ProfileStore(pluginWorkspace);
    }
    panel = new ExistdbPreferencesPanel(store);
    return panel.buildContent();
  }

  @Override
  public void apply(PluginWorkspace pluginWorkspace) {
    if (panel != null) {
      panel.save();
    }
  }

  @Override
  public void restoreDefaults() {
    if (panel != null) {
      panel.restoreDefaults();
    }
  }

  @Override
  public String getTitle() {
    return "eXist-db";
  }

  @Override
  public String getKey() {
    return KEY;
  }

  @Override
  public String[] getProjectLevelOptionKeys() {
    // Lights up the Global / Project Options radio. Connection definitions + display defaults can go
    // in the .xpr; usernames (per-user) and passwords (secret store) are deliberately excluded.
    return ProfileStore.projectLevelKeys();
  }
}
