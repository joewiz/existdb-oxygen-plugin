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
package com.existdb.oxygen;

import com.existdb.oxygen.client.ExistClient;
import com.existdb.oxygen.lang.LangServiceSupport;
import com.existdb.oxygen.model.ConnectionProfile;

import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide registry of connections, one {@link ExistClient} per saved profile (keyed by the
 * profile's stable id), plus the id of the default server. A resource's operations route to its
 * own server: the {@code exist:} URL stream handler, the validation engine, the auto-validator, and
 * "Run Current Editor" resolve the client from the {@code exist://<id>/…} location, falling back to
 * the default server for queries not stored in a database.
 */
public final class ExistContext {

  private static final Map<String, ExistClient> CLIENTS = new ConcurrentHashMap<>();
  private static volatile String defaultId;

  private ExistContext() {
  }

  /** Rebuilds the registry from the saved profiles and the default-server id. */
  public static void setProfiles(List<ConnectionProfile> profiles, String defaultProfileId) {
    Map<String, ExistClient> next = new ConcurrentHashMap<>();
    for (ConnectionProfile profile : profiles) {
      if (profile.getId() != null) {
        next.put(profile.getId(), new ExistClient(profile));
      }
    }
    CLIENTS.clear();
    CLIENTS.putAll(next);
    defaultId = defaultProfileId;
  }

  /** Registers a single profile as the only/default server (convenience for tests/simple flows). */
  public static void setActiveProfile(ConnectionProfile profile) {
    setProfiles(List.of(profile), profile.getId());
  }

  /** The client for the given profile id, or {@code null} if no such server is registered. */
  public static ExistClient clientById(String id) {
    return id == null ? null : CLIENTS.get(id);
  }

  /** The default server's client (for queries not tied to a specific database), or {@code null}. */
  public static ExistClient defaultClient() {
    return clientById(defaultId);
  }

  /** Back-compat: the default server's client. */
  public static ExistClient client() {
    return defaultClient();
  }

  /**
   * The client for an editor location: the server named in an {@code exist://<id>/…} URL, or the
   * default server for anything else (untitled editor, on-disk file).
   */
  public static ExistClient clientFor(URL location) {
    return location == null ? defaultClient() : clientForSystemId(location.toExternalForm());
  }

  /** As {@link #clientFor(URL)}, from a system-id string (e.g. a transformation Source's systemId). */
  public static ExistClient clientForSystemId(String systemId) {
    ExistClient client = clientById(LangServiceSupport.serverId(systemId));
    return client != null ? client : defaultClient();
  }

  public static boolean isConnected() {
    return defaultClient() != null;
  }
}
