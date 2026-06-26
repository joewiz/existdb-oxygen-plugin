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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.existdb.oxygen.client.ExistConnectionException;
import com.existdb.oxygen.client.ExistHttpException;
import com.existdb.oxygen.model.ConnectionProfile;
import com.existdb.oxygen.ui.ConnectionHealth.Status;
import java.io.IOException;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class ConnectionHealthTest {

  private static final ConnectionProfile PROFILE =
      new ConnectionProfile("Local", "http://localhost:18080/exist", "admin", "");

  private static ConnectionHealth health(Runnable onChange) {
    return new ConnectionHealth(List::of, onChange);
  }

  private static Status statusAfter(Throwable outcome) {
    ConnectionHealth h = health(() -> {
    });
    h.record("s1", outcome);
    return h.health("s1").status();
  }

  @Test
  void unknownBeforeAnySignal() {
    assertEquals(Status.UNKNOWN, health(() -> {
    }).health("s1").status());
  }

  @Test
  void successIsOnline() {
    assertEquals(Status.ONLINE, statusAfter(null));
  }

  @Test
  void connectionRefusedIsOffline() {
    ExistConnectionException offline =
        ExistConnectionException.from(PROFILE, new ConnectException("refused"));
    assertEquals(Status.OFFLINE, statusAfter(offline));
  }

  @Test
  void unauthorizedIsAuthFailed() {
    assertEquals(Status.AUTH_FAILED, statusAfter(new ExistHttpException(401, "GET /db", "")));
    assertEquals(Status.AUTH_FAILED, statusAfter(new ExistHttpException(403, "GET /db", "")));
  }

  @Test
  void otherHttpErrorStillCountsAsReachable() {
    // The server answered (a 404), so it's online even though the call failed.
    assertEquals(Status.ONLINE, statusAfter(new ExistHttpException(404, "GET /db", "missing")));
  }

  @Test
  void unrelatedIoErrorCountsAsReachable() {
    assertEquals(Status.ONLINE, statusAfter(new IOException("parse error")));
  }

  @Test
  void statusColorsAreDistinctAndUnknownHasNone() {
    assertNull(Status.UNKNOWN.color());
    assertNotNull(Status.ONLINE.color());
    assertNotNull(Status.OFFLINE.color());
    assertNotNull(Status.AUTH_FAILED.color());
  }

  @Test
  void onChangeFiresOnTransitionNotOnRepeat() throws Exception {
    AtomicInteger changes = new AtomicInteger();
    ConnectionHealth h = health(changes::incrementAndGet);
    // Run on the EDT so update() notifies synchronously (off-EDT it would be invokeLater'd).
    SwingUtilities.invokeAndWait(() -> {
      h.record("s1", null); // UNKNOWN -> ONLINE: fires
      h.record("s1", null); // ONLINE -> ONLINE: no fire
      h.record("s1", new ExistHttpException(401, "x", "")); // ONLINE -> AUTH_FAILED: fires
    });
    assertEquals(2, changes.get());
  }

  @Test
  void serversChangedForgetsRemovedServers() {
    ConnectionProfile profile = new ConnectionProfile("Local", "http://localhost:18080/exist",
        "admin", "");
    profile.setId("p1");
    List<ConnectionProfile> live = new ArrayList<>();
    live.add(profile);
    ConnectionHealth h = new ConnectionHealth(() -> live, () -> {
    });
    h.record("p1", null);
    assertEquals(Status.ONLINE, h.health("p1").status());

    live.clear(); // the profile was deleted
    h.serversChanged();
    assertEquals(Status.UNKNOWN, h.health("p1").status());
  }
}
