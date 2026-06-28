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
import com.existdb.oxygen.client.ExistClient;
import com.existdb.oxygen.client.ExistConnectionException;
import com.existdb.oxygen.client.ExistHttpException;
import com.existdb.oxygen.model.ConnectionProfile;
import java.awt.Color;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;

/**
 * Tracks whether each configured eXist-db server is reachable, so the pane can show a status dot
 * next to each server node.
 *
 * <p>Two signals feed it. <b>Passively</b>, every real API call the pane makes reports its outcome
 * via {@link #record} — a success (or any server response) means online, an
 * {@link ExistConnectionException} means offline, a 401/403 means the credentials were rejected;
 * this costs no extra requests. <b>Actively</b>, a gentle poll ({@code whoami}, the cheapest
 * authenticated call) runs on a timer <i>only while the pane is showing</i> so an idle, unwatched
 * pane sends nothing — it exists to notice a server coming back up or going down between user
 * actions. existdb-openapi is stateless HTTP (no persistent/websocket channel), so reachability is
 * only ever known from a request; this makes those requests cheap and well-timed rather than
 * pre-flighting every action.</p>
 */
final class ConnectionHealth {

  /** How often the active poll runs while the pane is showing. */
  private static final int POLL_INTERVAL_MS = 30_000;

  private static final Health UNKNOWN_HEALTH = new Health(Status.UNKNOWN, null);

  private final Map<String, Health> byServer = new ConcurrentHashMap<>();
  private final Supplier<List<ConnectionProfile>> profiles;
  private final Runnable onChange;
  private final Timer timer;
  private boolean polling;

  /** A server's reachability, with the dot color the renderer paints (null = no dot). */
  enum Status {
    UNKNOWN(null),
    ONLINE(new Color(0x3C, 0xA8, 0x4B)),
    OFFLINE(new Color(0xC0, 0x39, 0x2B)),
    AUTH_FAILED(new Color(0xE0, 0xA8, 0x00));

    private final Color color;

    Status(Color color) {
      this.color = color;
    }

    Color color() {
      return color;
    }
  }

  /** A server's current status plus a human-readable detail for the tooltip. */
  record Health(Status status, String detail) {
  }

  /**
   * @param profiles supplies the servers to poll (the saved connections)
   * @param onChange run on the EDT whenever any server's status changes (e.g. to repaint the tree)
   */
  ConnectionHealth(Supplier<List<ConnectionProfile>> profiles, Runnable onChange) {
    this.profiles = profiles;
    this.onChange = onChange;
    this.timer = new Timer(POLL_INTERVAL_MS, e -> poll());
    this.timer.setInitialDelay(0);
  }

  /** The current health of a server (never null; {@link Status#UNKNOWN} before the first check). */
  Health health(String serverId) {
    return byServer.getOrDefault(serverId, UNKNOWN_HEALTH);
  }

  /** Begins polling (call when the pane becomes visible); polls once immediately. */
  void start() {
    if (!timer.isRunning()) {
      timer.start();
    }
  }

  /** Stops polling (call when the pane is hidden) so an unwatched pane sends nothing. */
  void stop() {
    timer.stop();
  }

  /** Forces an immediate reachability re-check of every server (the pane's Refresh button). */
  void refresh() {
    poll();
  }

  /** Records the outcome of a real API call against a server (a free passive health signal). */
  void record(String serverId, Throwable failureOrNull) {
    update(serverId, failureOrNull == null ? new Health(Status.ONLINE, null) : classify(failureOrNull));
  }

  /** Forgets servers no longer in the list (e.g. deleted connections) and re-polls the rest. */
  void serversChanged() {
    byServer.keySet().removeIf(id -> profiles.get().stream().noneMatch(p -> id.equals(p.getId())));
    if (timer.isRunning()) {
      poll();
    }
  }

  private void poll() {
    if (polling) {
      return;
    }
    List<ConnectionProfile> snapshot = profiles.get();
    if (snapshot.isEmpty()) {
      return;
    }
    polling = true;
    new SwingWorker<Map<String, Health>, Void>() {
      @Override
      protected Map<String, Health> doInBackground() {
        Map<String, Health> out = new HashMap<>();
        for (ConnectionProfile profile : snapshot) {
          ExistClient client = ExistContext.clientById(profile.getId());
          if (client != null) {
            out.put(profile.getId(), probe(client));
          }
        }
        return out;
      }

      @Override
      protected void done() {
        polling = false;
        try {
          get().forEach(ConnectionHealth.this::update);
        } catch (java.util.concurrent.ExecutionException ignored) {
          // A failed probe is itself a status; nothing to do if the worker as a whole errored.
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }.execute();
  }

  /** Probes one server with the cheapest authenticated call and maps the outcome to a status. */
  private static Health probe(ExistClient client) {
    try {
      client.whoami();
      return new Health(Status.ONLINE, null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return UNKNOWN_HEALTH;
    } catch (Exception e) {
      return classify(e);
    }
  }

  /** Maps a failure to a status: unreachable → offline, 401/403 → auth, any server reply → online. */
  private static Health classify(Throwable cause) {
    if (cause instanceof ExistConnectionException) {
      return new Health(Status.OFFLINE, cause.getMessage());
    }
    if (cause instanceof ExistHttpException http
        && (http.getStatusCode() == 401 || http.getStatusCode() == 403)) {
      return new Health(Status.AUTH_FAILED, "Authentication failed (check the user and password).");
    }
    return new Health(Status.ONLINE, null);
  }

  private void update(String serverId, Health health) {
    Health previous = byServer.put(serverId, health);
    if (previous == null || previous.status() != health.status()) {
      if (SwingUtilities.isEventDispatchThread()) {
        onChange.run();
      } else {
        SwingUtilities.invokeLater(onChange);
      }
    }
  }
}
