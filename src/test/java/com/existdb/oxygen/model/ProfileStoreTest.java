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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link ProfileStore} list storage and legacy migration (in-memory options). */
class ProfileStoreTest {

  private static ProfileStore store(Map<String, String> backing) {
    return store(backing, new HashMap<>());
  }

  /** Map-backed options: {@code backing} for regular options, {@code secrets} for the secret store. */
  private static ProfileStore store(Map<String, String> backing, Map<String, String> secrets) {
    ProfileStore.Options options = new ProfileStore.Options() {
      @Override
      public String get(String key, String defaultValue) {
        return backing.getOrDefault(key, defaultValue);
      }

      @Override
      public void set(String key, String value) {
        backing.put(key, value);
      }

      @Override
      public String getSecret(String key, String defaultValue) {
        return secrets.getOrDefault(key, defaultValue);
      }

      @Override
      public void setSecret(String key, String value) {
        secrets.put(key, value);
      }
    };
    // Reversible "decryption" matching the legacy "enc:" prefix used by migration test fixtures.
    UnaryOperator<String> decrypt = s -> s.startsWith("enc:") ? s.substring(4) : s;
    return new ProfileStore(options, decrypt);
  }

  @Test
  void freshInstallYieldsOneDefaultProfileWithStableId() {
    Map<String, String> backing = new HashMap<>();
    ProfileStore store = store(backing);

    List<ConnectionProfile> first = store.loadAll();
    assertEquals(1, first.size());
    assertNotNull(first.get(0).getId());
    assertEquals("localhost 8080", first.get(0).getName());
    assertEquals(first.get(0).getId(), store.defaultProfileId());

    // The generated id is persisted, so a second load returns the same id.
    assertEquals(first.get(0).getId(), store.loadAll().get(0).getId());
  }

  @Test
  void migratesLegacySingleProfile() {
    Map<String, String> backing = new HashMap<>();
    backing.put("existdb.profile.name", "Prod");
    backing.put("existdb.profile.baseUrl", "https://db.example.org/exist/apps/existdb-openapi");
    backing.put("existdb.profile.user", "admin");
    backing.put("existdb.profile.password", "enc:secret");
    backing.put("existdb.profile.acceptSelfSigned", "true");
    ProfileStore store = store(backing);

    List<ConnectionProfile> all = store.loadAll();
    assertEquals(1, all.size());
    ConnectionProfile p = all.get(0);
    assertEquals("Prod", p.getName());
    assertEquals("admin", p.getUser());
    assertEquals("secret", p.getPassword());
    assertTrue(p.isAcceptSelfSigned());
    assertNotNull(p.getId());
    // Migration persists the new connections format.
    assertFalse(backing.get("existdb.connections.v2").isBlank());
    assertEquals(p.getId(), store.defaultProfileId());
  }

  @Test
  void saveAllRoundTripsMultipleProfilesAndPassword() {
    Map<String, String> backing = new HashMap<>();
    Map<String, String> secrets = new HashMap<>();
    ProfileStore store = store(backing, secrets);

    ConnectionProfile a = new ConnectionProfile("Local", "http://localhost:8080/x", "admin", "");
    ConnectionProfile b =
        new ConnectionProfile("Stg", "https://stg/x", "dev", "pw", true);
    store.saveAll(new java.util.ArrayList<>(List.of(a, b)));

    List<ConnectionProfile> all = store.loadAll();
    assertEquals(2, all.size());
    assertNotNull(all.get(0).getId());
    assertNotNull(all.get(1).getId());
    assertEquals("Stg", all.get(1).getName());
    assertEquals("pw", all.get(1).getPassword());
    assertTrue(all.get(1).isAcceptSelfSigned());
    // The password lives in the per-user secret store, never the regular (project-scopable) options.
    assertEquals("pw", secrets.get("existdb.secret." + all.get(1).getId()));
    assertFalse(backing.values().stream().anyMatch(v -> v.contains("pw")));
  }

  @Test
  void connectionsKeyExcludesUsernameAndPassword() {
    Map<String, String> backing = new HashMap<>();
    ProfileStore store = store(backing, new HashMap<>());
    store.saveAll(new java.util.ArrayList<>(List.of(
        new ConnectionProfile("Stg", "https://stg/x", "dev", "pw", true))));

    // The project-scopable connections key holds only definitions — never username or password.
    String json = backing.get("existdb.connections.v2");
    assertNotNull(json);
    assertFalse(json.contains("pw"), "password must not be in the project-scopable connections key");
    assertFalse(json.contains("dev"), "username must not be in the project-scopable connections key");
    for (String key : ProfileStore.projectLevelKeys()) {
      assertFalse(key.startsWith("existdb.secret."));
      assertFalse(key.endsWith(".user"));
    }
  }

  @Test
  void renameReslugsIdAndKeepsOldSlugAsAlias() {
    Map<String, String> backing = new HashMap<>();
    ProfileStore store = store(backing);
    String originalId = store.load().getId(); // "localhost-8080"

    store.save(new ConnectionProfile("Renamed", "http://h/x", "admin", ""));

    List<ConnectionProfile> all = store.loadAll();
    assertEquals(1, all.size());
    assertEquals("Renamed", all.get(0).getName());
    assertEquals("renamed", all.get(0).getId());
    assertTrue(all.get(0).getAliases().contains(originalId));
    assertEquals("renamed", store.defaultProfileId()); // the default follows the renamed server
  }

  @Test
  void defaultProfileIdIsSettable() {
    Map<String, String> backing = new HashMap<>();
    ProfileStore store = store(backing);
    ConnectionProfile a = new ConnectionProfile("A", "http://a/x", "u", "");
    ConnectionProfile b = new ConnectionProfile("B", "http://b/x", "u", "");
    store.saveAll(new java.util.ArrayList<>(List.of(a, b)));

    store.setDefaultProfileId(b.getId());
    assertEquals(b.getId(), store.defaultProfileId());
    assertEquals("B", store.load().getName());
  }

  @Test
  void openTabsRoundTripAndDefaultEmpty() {
    ProfileStore store = store(new HashMap<>());
    assertTrue(store.openTabs().isEmpty());

    store.setOpenTabs(List.of(
        "exist://srv-1/db/apps/myapp/index.xq",
        "exist://srv-1/db/data/notes.xml"));
    List<String> reloaded = store.openTabs();
    assertEquals(2, reloaded.size());
    assertEquals("exist://srv-1/db/apps/myapp/index.xq", reloaded.get(0));
    assertEquals("exist://srv-1/db/data/notes.xml", reloaded.get(1));

    store.setOpenTabs(List.of());
    assertTrue(store.openTabs().isEmpty());
  }

  @Test
  void registriesDefaultToPublicRepoThenPersist() {
    ProfileStore store = store(new HashMap<>());
    assertEquals(List.of(ProfileStore.DEFAULT_REGISTRY), store.registries());
    assertEquals(ProfileStore.DEFAULT_REGISTRY, store.selectedRegistry());

    store.setRegistries(
        List.of(ProfileStore.DEFAULT_REGISTRY, "https://example.org/exist/apps/repo"));
    assertEquals(2, store.registries().size());
    store.setSelectedRegistry("https://example.org/exist/apps/repo");
    assertEquals("https://example.org/exist/apps/repo", store.selectedRegistry());

    // A selection no longer in the list falls back to the first configured registry.
    store.setRegistries(List.of(ProfileStore.DEFAULT_REGISTRY));
    assertEquals(ProfileStore.DEFAULT_REGISTRY, store.selectedRegistry());
  }
}
