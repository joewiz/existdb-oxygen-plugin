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
package com.existdb.oxygen.model;

import ro.sync.exml.workspace.api.PluginWorkspace;
import ro.sync.exml.workspace.api.options.WSOptionsStorage;
import ro.sync.exml.workspace.api.util.UtilAccess;

/**
 * Persists the single active connection profile in Oxygen's options storage. The password is
 * stored using Oxygen's {@link UtilAccess#encrypt(String)} rather than in clear text.
 *
 * <p>MVP scope: one profile. Multi-server profiles are a later (P2) feature.</p>
 */
public final class ProfileStore {

  private static final String KEY_NAME = "existdb.profile.name";
  private static final String KEY_URL = "existdb.profile.baseUrl";
  private static final String KEY_USER = "existdb.profile.user";
  private static final String KEY_PASS = "existdb.profile.password";
  private static final String KEY_ACCEPT_SELF_SIGNED = "existdb.profile.acceptSelfSigned";

  private final WSOptionsStorage options;
  private final UtilAccess util;

  public ProfileStore(PluginWorkspace workspace) {
    this.options = workspace.getOptionsStorage();
    this.util = workspace.getUtilAccess();
  }

  public ConnectionProfile load() {
    ConnectionProfile def = new ConnectionProfile();
    String name = options.getOption(KEY_NAME, def.getName());
    String url = options.getOption(KEY_URL, def.getBaseUrl());
    String user = options.getOption(KEY_USER, def.getUser());
    String encrypted = options.getOption(KEY_PASS, "");
    String pass = encrypted.isEmpty() ? "" : util.decrypt(encrypted);
    boolean acceptSelfSigned =
        Boolean.parseBoolean(options.getOption(KEY_ACCEPT_SELF_SIGNED, "false"));
    return new ConnectionProfile(name, url, user, pass == null ? "" : pass, acceptSelfSigned);
  }

  public void save(ConnectionProfile profile) {
    options.setOption(KEY_NAME, profile.getName());
    options.setOption(KEY_URL, profile.getBaseUrl());
    options.setOption(KEY_USER, profile.getUser());
    String pass = profile.getPassword();
    options.setOption(KEY_PASS, pass == null || pass.isEmpty() ? "" : util.encrypt(pass));
    options.setOption(KEY_ACCEPT_SELF_SIGNED, Boolean.toString(profile.isAcceptSelfSigned()));
  }
}
