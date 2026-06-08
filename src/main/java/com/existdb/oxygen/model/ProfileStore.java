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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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

  /** The default package registry (eXist-db's public-repo), always present if the list is empty. */
  public static final String DEFAULT_REGISTRY = "https://exist-db.org/exist/apps/public-repo";

  // Legacy single-profile keys (pre-multi-server), read once for migration.
  private static final String LEGACY_NAME = "existdb.profile.name";
  private static final String LEGACY_URL = "existdb.profile.baseUrl";
  private static final String LEGACY_USER = "existdb.profile.user";
  private static final String LEGACY_PASS = "existdb.profile.password";
  private static final String LEGACY_ACCEPT = "existdb.profile.acceptSelfSigned";

  private final Options options;
  private final UnaryOperator<String> encrypt;
  private final UnaryOperator<String> decrypt;
  /** Notified when the result-display defaults change, so an open results view can re-apply them. */
  private final List<Runnable> resultsPrefsListeners = new ArrayList<>();

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

  /**
   * Persists the given profiles, assigning each a name-derived slug id (unique across the set) and,
   * on a rename, keeping the old slug as an alias so open {@code exist://} editors still resolve.
   * Keeps the default id valid.
   */
  public void saveAll(List<ConnectionProfile> profiles) {
    Set<String> taken = new HashSet<>();
    List<String> ids = new ArrayList<>(profiles.size());
    for (ConnectionProfile profile : profiles) {
      assignId(profile, taken);
      taken.add(profile.getId());
      writeProfile(profile);
      ids.add(profile.getId());
    }
    options.set(KEY_IDS, String.join(",", ids));
    if (!ids.contains(defaultProfileId())) {
      options.set(KEY_DEFAULT, ids.isEmpty() ? "" : ids.get(0));
    }
  }

  /**
   * Assigns {@code profile}'s id: keeps the current one if the name still slugs to the same base
   * (so a cosmetic edit or reorder doesn't churn ids); otherwise mints a fresh unique slug and
   * records the old id as an alias.
   */
  private void assignId(ConnectionProfile profile, Set<String> taken) {
    String base = slugify(profile.getName());
    String current = profile.getId();
    if (current != null && !taken.contains(current)) {
      String priorName = options.get(PROFILE_PREFIX + current + ".name", null);
      if (priorName != null && slugify(priorName).equals(base)) {
        return; // name unchanged — keep the existing (possibly suffixed) id
      }
    }
    String id = makeUnique(base, taken);
    profile.setId(id); // set first so addAlias doesn't reject the old id as "the current id"
    if (current != null && !current.equals(id)) {
      profile.addAlias(current);
    }
  }

  private static String slugify(String name) {
    if (name == null) {
      return "server";
    }
    String slug = name.toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9]+", "-")
        .replaceAll("(^-+|-+$)", "");
    return slug.isEmpty() ? "server" : slug;
  }

  private static String makeUnique(String base, Set<String> taken) {
    if (!taken.contains(base)) {
      return base;
    }
    for (int i = 2; ; i++) {
      String candidate = base + "-" + i;
      if (!taken.contains(candidate)) {
        return candidate;
      }
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
  // Result-display preferences (global; the eXist-db Results view's starting state)
  // ---------------------------------------------------------------------------

  public String resultsMethod() {
    return options.get("existdb.results.method", "adaptive");
  }

  public void setResultsMethod(String method) {
    options.set("existdb.results.method", method);
  }

  public boolean resultsIndent() {
    return Boolean.parseBoolean(options.get("existdb.results.indent", "true"));
  }

  public void setResultsIndent(boolean indent) {
    options.set("existdb.results.indent", Boolean.toString(indent));
  }

  public int resultsPageSize() {
    try {
      return Integer.parseInt(options.get("existdb.results.pageSize", "10"));
    } catch (NumberFormatException e) {
      return 10;
    }
  }

  public void setResultsPageSize(int pageSize) {
    options.set("existdb.results.pageSize", Integer.toString(pageSize));
  }

  /** Where a query's results go by default: {@code "browse"} (Results view) or {@code "editor"}. */
  public String resultsDestination() {
    return options.get("existdb.results.destination", "browse");
  }

  public void setResultsDestination(String destination) {
    options.set("existdb.results.destination", destination);
  }

  /**
   * The XQuery validation/transformation engine that was Oxygen's default before the user switched
   * it to eXist-db (HTTP) via our toggle — remembered so unchecking the toggle restores it exactly
   * (rather than guessing a Saxon variant/version).
   */
  public String priorXQueryEngine() {
    return options.get("existdb.xquery.priorEngine", "");
  }

  public void setPriorXQueryEngine(String engine) {
    options.set("existdb.xquery.priorEngine", engine);
  }

  /** The configured package registries (base URLs); the default public-repo when none are set. */
  public List<String> registries() {
    List<String> out = new ArrayList<>();
    for (String url : options.get("existdb.registries", "").split("\n")) {
      if (!url.isBlank()) {
        out.add(url.trim());
      }
    }
    if (out.isEmpty()) {
      out.add(DEFAULT_REGISTRY);
    }
    return out;
  }

  public void setRegistries(List<String> registries) {
    options.set("existdb.registries", String.join("\n", registries));
  }

  /** The registry currently selected for update-check/install; the first configured one by default. */
  public String selectedRegistry() {
    String selected = options.get("existdb.registry.selected", "");
    List<String> all = registries();
    return all.contains(selected) ? selected : all.get(0);
  }

  public void setSelectedRegistry(String registry) {
    options.set("existdb.registry.selected", registry == null ? "" : registry);
  }

  /**
   * Whether hidden files/collections (dot-prefixed names) are shown in the eXist-db pane. Off by
   * default, mirroring the Project pane's filters. Independent of {@link #uploadHidden()}.
   */
  public boolean showHidden() {
    return Boolean.parseBoolean(options.get("existdb.showHidden", "false"));
  }

  public void setShowHidden(boolean showHidden) {
    options.set("existdb.showHidden", Boolean.toString(showHidden));
  }

  /**
   * Whether hidden files/directories (dot-prefixed) are included when uploading a folder. Off by
   * default so {@code .git}, {@code .DS_Store}, etc. aren't uploaded. Independent of
   * {@link #showHidden()} (viewing and uploading are separate concerns).
   */
  public boolean uploadHidden() {
    return Boolean.parseBoolean(options.get("existdb.uploadHidden", "false"));
  }

  public void setUploadHidden(boolean uploadHidden) {
    options.set("existdb.uploadHidden", Boolean.toString(uploadHidden));
  }

  /**
   * The {@code exist://} editor locations that were open at last shutdown, in tab order, so they can
   * be reopened on the next start (Oxygen restores {@code file:} tabs but not custom-protocol ones).
   * Stored newline-separated.
   */
  public List<String> openTabs() {
    List<String> out = new ArrayList<>();
    for (String url : options.get("existdb.openTabs", "").split("\n")) {
      if (!url.isBlank()) {
        out.add(url.trim());
      }
    }
    return out;
  }

  public void setOpenTabs(List<String> urls) {
    options.set("existdb.openTabs", String.join("\n", urls));
  }

  /**
   * Whether the eXist-db pane re-expands, on startup, the servers and collections that were open at
   * last shutdown (re-fetching them live). On by default; turning it off gives a clean, fast pane.
   */
  public boolean restorePane() {
    return Boolean.parseBoolean(options.get("existdb.restorePane", "true"));
  }

  public void setRestorePane(boolean restore) {
    options.set("existdb.restorePane", Boolean.toString(restore));
  }

  /**
   * The {@code exist://} collection locations expanded in the pane at last shutdown (the server node
   * is {@code …/db}), used to restore the tree's expansion state. Stored newline-separated.
   */
  public List<String> expandedCollections() {
    List<String> out = new ArrayList<>();
    for (String url : options.get("existdb.expandedCollections", "").split("\n")) {
      if (!url.isBlank()) {
        out.add(url.trim());
      }
    }
    return out;
  }

  public void setExpandedCollections(List<String> paths) {
    options.set("existdb.expandedCollections", String.join("\n", paths));
  }

  /**
   * Absolute paths of project directories the user has approved for running build commands (the
   * "don't ask again" set behind the build trust gate). Stored newline-separated.
   */
  public List<String> trustedBuildDirs() {
    List<String> out = new ArrayList<>();
    for (String dir : options.get("existdb.trustedBuildDirs", "").split("\n")) {
      if (!dir.isBlank()) {
        out.add(dir.trim());
      }
    }
    return out;
  }

  public boolean isBuildDirTrusted(String absolutePath) {
    return trustedBuildDirs().contains(absolutePath);
  }

  public void addTrustedBuildDir(String absolutePath) {
    List<String> dirs = trustedBuildDirs();
    if (!dirs.contains(absolutePath)) {
      dirs.add(absolutePath);
      options.set("existdb.trustedBuildDirs", String.join("\n", dirs));
    }
  }

  /** Registers a callback run whenever {@link #notifyResultsPrefsChanged()} is invoked. */
  public void addResultsPrefsListener(Runnable listener) {
    resultsPrefsListeners.add(listener);
  }

  /** Signals that the result-display defaults were edited so listeners can re-apply them live. */
  public void notifyResultsPrefsChanged() {
    resultsPrefsListeners.forEach(Runnable::run);
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
    // id left null so saveAll assigns the name-derived slug.
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
    String aliases = options.get(prefix + "aliases", "");
    if (!aliases.isBlank()) {
      profile.setAliases(Arrays.asList(aliases.split(",")));
    }
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
    options.set(prefix + "aliases", String.join(",", profile.getAliases()));
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
