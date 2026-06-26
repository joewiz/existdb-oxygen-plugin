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
package com.existdb.oxygen.client;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.existdb.oxygen.model.ConnectionProfile;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

class ExistConnectionExceptionTest {

  private static final ConnectionProfile PROFILE =
      new ConnectionProfile("Local", "http://localhost:18080/exist", "admin", "");

  @Test
  void connectionRefusedNamesServerAndNeverShowsNull() {
    // A ConnectException from HttpClient often carries a null message ("Query failed: null").
    ExistConnectionException ex = ExistConnectionException.from(PROFILE, new ConnectException());
    assertNotNull(ex);
    assertNotNull(ex.getMessage());
    assertTrue(ex.getMessage().contains("Can't reach"), ex.getMessage());
    assertTrue(ex.getMessage().contains("Local"), ex.getMessage());
    assertTrue(ex.getMessage().contains("http://localhost:18080/exist"), ex.getMessage());
  }

  @Test
  void connectTimeoutReadsAsUnreachable() {
    ExistConnectionException ex =
        ExistConnectionException.from(PROFILE, new HttpConnectTimeoutException("timed out"));
    assertNotNull(ex);
    assertTrue(ex.getMessage().contains("Can't reach"), ex.getMessage());
  }

  @Test
  void unknownHostPointsAtTheHostName() {
    ExistConnectionException ex =
        ExistConnectionException.from(PROFILE, new UnknownHostException("nope"));
    assertNotNull(ex);
    assertTrue(ex.getMessage().contains("resolve the host"), ex.getMessage());
  }

  @Test
  void readTimeoutReadsAsNoResponse() {
    ExistConnectionException ex =
        ExistConnectionException.from(PROFILE, new HttpTimeoutException("slow"));
    assertNotNull(ex);
    assertTrue(ex.getMessage().contains("didn't respond"), ex.getMessage());
  }

  @Test
  void tlsFailureSuggestsTheSelfSignedToggle() {
    ExistConnectionException ex =
        ExistConnectionException.from(PROFILE, new SSLHandshakeException("bad cert"));
    assertNotNull(ex);
    assertTrue(ex.getMessage().contains("self-signed"), ex.getMessage());
  }

  @Test
  void unrelatedIoErrorIsLeftAlone() {
    // Not a connection problem — caller should rethrow the original, so from() returns null.
    assertNull(ExistConnectionException.from(PROFILE, new IOException("disk full")));
  }

  @Test
  void preservesTheUnderlyingCause() {
    ConnectException cause = new ConnectException("Connection refused");
    ExistConnectionException ex = ExistConnectionException.from(PROFILE, cause);
    assertNotNull(ex);
    assertSame(cause, ex.getCause());
  }
}
