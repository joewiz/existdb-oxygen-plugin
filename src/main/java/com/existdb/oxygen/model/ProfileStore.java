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

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
  /** Project-scopable key: a JSON array of connection *definitions* (id/name/URL/trust/aliases) —
   *  no username or password, so a shared {@code .xpr} never carries credentials. */
  private static final String KEY_CONNECTIONS = "existdb.connections.v2";
  /** Per-user secret store prefix for a connection's password (never project-scoped). */
  private static final String SECRET_PREFIX = "existdb.secret.";
  /** Key for the per-project map of open {@code exist://} editor locations (JSON: project URL → list). */
  private static final String KEY_OPEN_TABS_BY_PROJECT = "existdb.openTabsByProject";

  /** The default package registry (eXist-db's public-repo), always present if the list is empty. */
  public static final String DEFAULT_REGISTRY = "https://exist-db.org/exist/apps/public-repo";

  // Legacy single-profile keys (pre-multi-server), read once for migration.
  private static final String LEGACY_NAME = "existdb.profile.name";
  private static final String LEGACY_URL = "existdb.profile.baseUrl";
  private static final String LEGACY_USER = "existdb.profile.user";
  private static final String LEGACY_PASS = "existdb.profile.password";
  private static final String LEGACY_ACCEPT = "existdb.profile.acceptSelfSigned";

  private final Options options;
  /** Decrypts legacy (pre-secret-store) passwords during migration; new passwords use the secret
   *  store, which does its own encryption. */
  private final UnaryOperator<String> decrypt;
  /** Notified when the result-display defaults change, so an open results view can re-apply them. */
  private final List<Runnable> resultsPrefsListeners = new ArrayList<>();
  /** Notified when the saved connections change, so the eXist-db pane can rebuild its server nodes. */
  private final List<Runnable> connectionsListeners = new ArrayList<>();

  /** Minimal string options backend, so the storage logic is testable without Oxygen. */
  public interface Options {
    String get(String key, String defaultValue);

    void set(String key, String value);

    /** Reads from the per-user secret store (Oxygen's {@code getSecretOption}). */
    String getSecret(String key, String defaultValue);

    /** Writes to the per-user secret store (Oxygen's {@code setSecretOption}); never project-scoped. */
    void setSecret(String key, String value);
  }

  public ProfileStore(PluginWorkspace workspace) {
    this(adapt(workspace.getOptionsStorage()), workspace.getUtilAccess()::decrypt);
  }

  ProfileStore(Options options, UnaryOperator<String> decrypt) {
    this.options = options;
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
    String json = options.get(KEY_CONNECTIONS, "");
    if (json.isBlank()) {
      return migrate();
    }
    List<ConnectionProfile> profiles = new ArrayList<>();
    JSONArray array = new JSONArray(json);
    for (int i = 0; i < array.length(); i++) {
      profiles.add(fromJson(array.getJSONObject(i)));
    }
    return profiles.isEmpty() ? migrate() : profiles;
  }

  /**
   * Persists the given profiles, assigning each a name-derived slug id (unique across the set) and,
   * on a rename, keeping the old slug as an alias so open {@code exist://} editors still resolve.
   * The connection <em>definitions</em> go to one project-scopable key; the username (per-user) and
   * password (per-user secret store) are kept out of it, so a shared {@code .xpr} never has creds.
   */
  public void saveAll(List<ConnectionProfile> profiles) {
    saveAllWith(profiles, currentNamesById());
  }

  private void saveAllWith(List<ConnectionProfile> profiles, Map<String, String> priorNames) {
    Set<String> taken = new HashSet<>();
    List<String> ids = new ArrayList<>(profiles.size());
    JSONArray definitions = new JSONArray();
    for (ConnectionProfile profile : profiles) {
      assignId(profile, taken, priorNames);
      taken.add(profile.getId());
      // Credentials are per-user: username stays global, password goes to the secret store.
      options.set(PROFILE_PREFIX + profile.getId() + ".user",
          profile.getUser() == null ? "" : profile.getUser());
      String pass = profile.getPassword();
      options.setSecret(SECRET_PREFIX + profile.getId(), pass == null ? "" : pass);
      definitions.put(toJson(profile));
      ids.add(profile.getId());
    }
    options.set(KEY_CONNECTIONS, definitions.toString());
    String currentDefault = options.get(KEY_DEFAULT, "");
    if (currentDefault.isBlank() || !ids.contains(currentDefault)) {
      options.set(KEY_DEFAULT, ids.isEmpty() ? "" : ids.get(0));
    }
  }

  /** id → name for the currently saved connections, so {@link #assignId} can detect a rename. */
  private Map<String, String> currentNamesById() {
    Map<String, String> names = new HashMap<>();
    String json = options.get(KEY_CONNECTIONS, "");
    if (!json.isBlank()) {
      JSONArray array = new JSONArray(json);
      for (int i = 0; i < array.length(); i++) {
        JSONObject o = array.getJSONObject(i);
        names.put(o.optString("id", ""), o.optString("name", ""));
      }
    }
    return names;
  }

  /** A connection definition (no credentials) as JSON for the project-scopable connections key. */
  private static JSONObject toJson(ConnectionProfile profile) {
    JSONObject o = new JSONObject();
    o.put("id", profile.getId());
    o.put("name", profile.getName());
    o.put("baseUrl", profile.getBaseUrl());
    o.put("acceptSelfSigned", profile.isAcceptSelfSigned());
    o.put("aliases", new JSONArray(profile.getAliases()));
    return o;
  }

  /** Rebuilds a profile from its definition plus the per-user username and secret password. */
  private ConnectionProfile fromJson(JSONObject o) {
    String id = o.optString("id", "");
    ConnectionProfile profile = new ConnectionProfile(
        o.optString("name", ""),
        ConnectionProfile.normalizeBaseUrl(o.optString("baseUrl", "")),
        options.get(PROFILE_PREFIX + id + ".user", ""),
        options.getSecret(SECRET_PREFIX + id, ""),
        o.optBoolean("acceptSelfSigned", false));
    profile.setId(id);
    JSONArray aliases = o.optJSONArray("aliases");
    if (aliases != null && !aliases.isEmpty()) {
      List<String> list = new ArrayList<>(aliases.length());
      for (int i = 0; i < aliases.length(); i++) {
        list.add(aliases.getString(i));
      }
      profile.setAliases(list);
    }
    return profile;
  }

  /**
   * Assigns {@code profile}'s id: keeps the current one if the name still slugs to the same base
   * (so a cosmetic edit or reorder doesn't churn ids); otherwise mints a fresh unique slug and
   * records the old id as an alias.
   */
  private void assignId(ConnectionProfile profile, Set<String> taken, Map<String, String> priorNames) {
    String base = slugify(profile.getName());
    String current = profile.getId();
    if (current != null && !taken.contains(current)) {
      String priorName = priorNames.get(current);
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

  /** The default server's id (for unsaved/local queries); falls back to the first saved connection. */
  public String defaultProfileId() {
    String stored = options.get(KEY_DEFAULT, "");
    if (!stored.isBlank()) {
      return stored;
    }
    String json = options.get(KEY_CONNECTIONS, "");
    if (!json.isBlank()) {
      JSONArray array = new JSONArray(json);
      if (!array.isEmpty()) {
        return array.getJSONObject(0).optString("id", "");
      }
    }
    return "";
  }

  public void setDefaultProfileId(String id) {
    options.set(KEY_DEFAULT, id == null ? "" : id);
  }

  /**
   * The option keys that an option page may store at <b>project</b> level (in the {@code .xpr}): the
   * connection <em>definitions</em> and the display/browsing defaults. Deliberately excludes the
   * per-user username keys and the secret-store passwords, so credentials never reach a shared
   * {@code .xpr}.
   */
  public static String[] projectLevelKeys() {
    return new String[] {
      KEY_CONNECTIONS, KEY_DEFAULT,
      "existdb.results.method", "existdb.results.indent", "existdb.results.wrap",
      "existdb.results.pageSize",
      "existdb.results.destination", "existdb.showHidden", "existdb.uploadHidden",
      "existdb.restorePane",
    };
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

  public boolean resultsWrap() {
    return Boolean.parseBoolean(options.get("existdb.results.wrap", "true"));
  }

  public void setResultsWrap(boolean wrap) {
    options.set("existdb.results.wrap", Boolean.toString(wrap));
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
   * The legacy single global list of {@code exist://} editor locations open at last shutdown. Retained
   * only to seed the per-project map on a one-time upgrade migration; new state is keyed per project
   * (see {@link #openTabs(String)}).
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
   * The {@code exist://} editor locations open under the given project (keyed by its URL), in tab
   * order, so they can be reopened when that project is loaded — Oxygen associates {@code file:} tabs
   * with a project and swaps them on project switch, but excludes custom-protocol ones, so we keep
   * our own per-project list. An empty/unknown key (no project) is a valid key.
   */
  public List<String> openTabs(String projectKey) {
    String json = options.get(KEY_OPEN_TABS_BY_PROJECT, "");
    if (json.isBlank()) {
      return new ArrayList<>();
    }
    JSONArray arr = new JSONObject(json).optJSONArray(projectKey);
    List<String> out = new ArrayList<>();
    if (arr != null) {
      for (int i = 0; i < arr.length(); i++) {
        String url = arr.optString(i, "");
        if (!url.isBlank()) {
          out.add(url);
        }
      }
    }
    return out;
  }

  public void setOpenTabs(String projectKey, List<String> urls) {
    String json = options.get(KEY_OPEN_TABS_BY_PROJECT, "");
    JSONObject map = json.isBlank() ? new JSONObject() : new JSONObject(json);
    if (urls.isEmpty()) {
      map.remove(projectKey);
    } else {
      map.put(projectKey, new JSONArray(urls));
    }
    options.set(KEY_OPEN_TABS_BY_PROJECT, map.toString());
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

  /** Registers a callback run whenever {@link #notifyConnectionsChanged()} is invoked. */
  public void addConnectionsListener(Runnable listener) {
    connectionsListeners.add(listener);
  }

  /** Signals that the saved connections changed (e.g. from the Preferences page) so the eXist-db
   *  pane can rebuild its server nodes. */
  public void notifyConnectionsChanged() {
    connectionsListeners.forEach(Runnable::run);
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
    Map<String, String> oldNames = new HashMap<>();
    List<ConnectionProfile> all = readOldFormat(oldNames);
    // Rewrite in the v2 format, preserving the existing ids (names unchanged) so open exist://
    // editors keep resolving, and moving passwords into the per-user secret store.
    saveAllWith(all, oldNames);
    if (defaultProfileId().isBlank() && !all.isEmpty()) {
      setDefaultProfileId(all.get(0).getId());
    }
    return all;
  }

  /**
   * Reads whatever pre-v2 storage exists: the per-id multi-profile keys, the legacy single profile,
   * or (fresh install) a single default localhost profile. Fills {@code namesById} (id → name) so the
   * subsequent save keeps each id stable.
   */
  private List<ConnectionProfile> readOldFormat(Map<String, String> namesById) {
    String ids = options.get(KEY_IDS, "");
    if (!ids.isBlank()) {
      List<ConnectionProfile> list = new ArrayList<>();
      for (String id : ids.split(",")) {
        String trimmed = id.trim();
        if (!trimmed.isBlank()) {
          ConnectionProfile p = readProfile(trimmed);
          namesById.put(trimmed, p.getName());
          list.add(p);
        }
      }
      if (!list.isEmpty()) {
        return list;
      }
    }
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
    // id left null so the save assigns the name-derived slug.
    return new ArrayList<>(List.of(profile));
  }

  private ConnectionProfile readProfile(String id) {
    String prefix = PROFILE_PREFIX + id + ".";
    ConnectionProfile def = new ConnectionProfile();
    String enc = options.get(prefix + "password", "");
    String pass = enc.isEmpty() ? "" : decrypt.apply(enc);
    ConnectionProfile profile = new ConnectionProfile(
        options.get(prefix + "name", def.getName()),
        ConnectionProfile.normalizeBaseUrl(options.get(prefix + "baseUrl", def.getBaseUrl())),
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

      @Override
      public String getSecret(String key, String defaultValue) {
        return storage.getSecretOption(key, defaultValue);
      }

      @Override
      public void setSecret(String key, String value) {
        storage.setSecretOption(key, value);
      }
    };
  }
}
