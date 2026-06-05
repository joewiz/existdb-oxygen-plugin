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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Persists the saved eXist connection profiles in Oxygen's options storage as an id-keyed list,
 * plus the id of the default server (used for validating/running queries not stored in a database).
 * Passwords are stored using Oxygen's {@link UtilAccess#encrypt(String)} rather than in clear text.
 *
 * <p>The options access is abstracted behind {@link Options} so the list/migration logic can be
 * unit-tested with a plain in-memory map; the public constructor adapts Oxygen's storage.</p>
 */
public final class ProfileStore {

  private static final String KEY_IDS = "existdb.profiles.ids";
  private static final String KEY_DEFAULT = "existdb.profiles.defaultId";
  private static final String PROFILE_PREFIX = "existdb.profile.";

  // Legacy single-profile keys (pre-multi-server), read once for migration.
  private static final String LEGACY_NAME = "existdb.profile.name";
  private static final String LEGACY_URL = "existdb.profile.baseUrl";
  private static final String LEGACY_USER = "existdb.profile.user";
  private static final String LEGACY_PASS = "existdb.profile.password";
  private static final String LEGACY_ACCEPT = "existdb.profile.acceptSelfSigned";

  private final Options options;
  private final UnaryOperator<String> encrypt;
  private final UnaryOperator<String> decrypt;

  /** Minimal string options backend, so the storage logic is testable without Oxygen. */
  public interface Options {
    String get(String key, String defaultValue);

    void set(String key, String value);
  }

  public ProfileStore(PluginWorkspace workspace) {
    this(adapt(workspace.getOptionsStorage()),
        workspace.getUtilAccess()::encrypt, workspace.getUtilAccess()::decrypt);
  }

  ProfileStore(Options options, UnaryOperator<String> encrypt, UnaryOperator<String> decrypt) {
    this.options = options;
    this.encrypt = encrypt;
    this.decrypt = decrypt;
  }

  // ---------------------------------------------------------------------------
  // Multi-profile API
  // ---------------------------------------------------------------------------

  /**
   * All saved profiles, in order. Never empty: a brand-new install yields the default
   * {@code localhost 8080} profile, and a legacy single-profile install is migrated to a one-element
   * list (persisted, so the generated id is stable across loads).
   */
  public List<ConnectionProfile> loadAll() {
    String ids = options.get(KEY_IDS, "");
    if (ids.isBlank()) {
      return migrate();
    }
    List<ConnectionProfile> profiles = new ArrayList<>();
    for (String id : ids.split(",")) {
      if (!id.isBlank()) {
        profiles.add(readProfile(id.trim()));
      }
    }
    return profiles.isEmpty() ? migrate() : profiles;
  }

  /** Persists the given profiles (assigning ids where missing) and keeps the default id valid. */
  public void saveAll(List<ConnectionProfile> profiles) {
    List<String> ids = new ArrayList<>(profiles.size());
    for (ConnectionProfile profile : profiles) {
      if (profile.getId() == null || profile.getId().isBlank()) {
        profile.setId(newId());
      }
      writeProfile(profile);
      ids.add(profile.getId());
    }
    options.set(KEY_IDS, String.join(",", ids));
    if (!ids.contains(defaultProfileId())) {
      options.set(KEY_DEFAULT, ids.isEmpty() ? "" : ids.get(0));
    }
  }

  /** The default server's id (for unsaved/local queries); falls back to the first profile. */
  public String defaultProfileId() {
    String stored = options.get(KEY_DEFAULT, "");
    if (!stored.isBlank()) {
      return stored;
    }
    String ids = options.get(KEY_IDS, "");
    return ids.isBlank() ? "" : ids.split(",")[0].trim();
  }

  public void setDefaultProfileId(String id) {
    options.set(KEY_DEFAULT, id == null ? "" : id);
  }

  // ---------------------------------------------------------------------------
  // Back-compat single-profile API (used until the pane is reworked for multi-server)
  // ---------------------------------------------------------------------------

  /** The default profile (the one used when a query isn't tied to a specific server). */
  public ConnectionProfile load() {
    List<ConnectionProfile> all = loadAll();
    String defaultId = defaultProfileId();
    return all.stream()
        .filter(p -> p.getId() != null && p.getId().equals(defaultId))
        .findFirst()
        .orElse(all.get(0));
  }

  /** Updates the default profile in place (preserving its id), or creates the first one. */
  public void save(ConnectionProfile profile) {
    List<ConnectionProfile> all = loadAll();
    ConnectionProfile target = load();
    profile.setId(target.getId());
    for (int i = 0; i < all.size(); i++) {
      if (all.get(i).getId().equals(target.getId())) {
        all.set(i, profile);
      }
    }
    saveAll(all);
  }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  private List<ConnectionProfile> migrate() {
    ConnectionProfile profile;
    if (!options.get(LEGACY_NAME, "").isBlank()) {
      String enc = options.get(LEGACY_PASS, "");
      String pass = enc.isEmpty() ? "" : decrypt.apply(enc);
      profile = new ConnectionProfile(
          options.get(LEGACY_NAME, ""), options.get(LEGACY_URL, ""), options.get(LEGACY_USER, ""),
          pass == null ? "" : pass,
          Boolean.parseBoolean(options.get(LEGACY_ACCEPT, "false")));
    } else {
      profile = new ConnectionProfile();
    }
    profile.setId(newId());
    List<ConnectionProfile> all = new ArrayList<>(List.of(profile));
    saveAll(all);
    setDefaultProfileId(profile.getId());
    return all;
  }

  private ConnectionProfile readProfile(String id) {
    String prefix = PROFILE_PREFIX + id + ".";
    ConnectionProfile def = new ConnectionProfile();
    String enc = options.get(prefix + "password", "");
    String pass = enc.isEmpty() ? "" : decrypt.apply(enc);
    ConnectionProfile profile = new ConnectionProfile(
        options.get(prefix + "name", def.getName()),
        options.get(prefix + "baseUrl", def.getBaseUrl()),
        options.get(prefix + "user", def.getUser()),
        pass == null ? "" : pass,
        Boolean.parseBoolean(options.get(prefix + "acceptSelfSigned", "false")));
    profile.setId(id);
    return profile;
  }

  private void writeProfile(ConnectionProfile profile) {
    String prefix = PROFILE_PREFIX + profile.getId() + ".";
    options.set(prefix + "name", profile.getName());
    options.set(prefix + "baseUrl", profile.getBaseUrl());
    options.set(prefix + "user", profile.getUser());
    String pass = profile.getPassword();
    options.set(prefix + "password", pass == null || pass.isEmpty() ? "" : encrypt.apply(pass));
    options.set(prefix + "acceptSelfSigned", Boolean.toString(profile.isAcceptSelfSigned()));
  }

  private static String newId() {
    return UUID.randomUUID().toString();
  }

  private static Options adapt(WSOptionsStorage storage) {
    return new Options() {
      @Override
      public String get(String key, String defaultValue) {
        return storage.getOption(key, defaultValue);
      }

      @Override
      public void set(String key, String value) {
        storage.setOption(key, value);
      }
    };
  }
}
