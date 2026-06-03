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
import com.existdb.oxygen.model.ConnectionProfile;

/**
 * Process-wide holder for the active connection. The collection view sets it once the user
 * configures/tests a connection; the {@code exist:} URL stream handler reads it to authenticate
 * open/save requests for resources the user opened from the tree.
 */
public final class ExistContext {

  private static volatile ExistClient client;

  private ExistContext() {
  }

  public static void setActiveProfile(ConnectionProfile profile) {
    client = new ExistClient(profile);
  }

  public static ExistClient client() {
    return client;
  }

  public static boolean isConnected() {
    return client != null;
  }
}
