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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.existdb.oxygen.model.ConnectionProfile;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration test: runs {@link ExistClient} against a real eXist 7 + existdb-openapi in Docker.
 * Activated by the {@code it} Maven profile ({@code mvn verify -Pit}); requires a Docker daemon.
 *
 * <p>The container is started manually (rather than via the {@code @Testcontainers} extension) so
 * the {@link #startContainer()} assumption can <em>skip</em> the test where Testcontainers can't
 * reach Docker, instead of failing the build. CI runners expose the socket natively, so it runs
 * there. The published image ships existdb-openapi, so no extra install step is needed.</p>
 */
// The "IT" suffix is the Maven Failsafe integration-test convention, which the default PMD
// ClassNamingConventions rule (expecting *Test) flags; the suffix is required here.
@SuppressWarnings("PMD.ClassNamingConventions")
class ExistClientIT {

  // NOTE: the resource round-trip below exercises the consolidated content endpoint
  // (existdb-openapi#59 — raw GET/PUT /api/db/resource?path=). The published beta3 image still
  // serves the old JSON-envelope/path-in-URL contract, so against it the resource assertions fail;
  // override with -Dexistdb.docker.image=<an image carrying #59> until a published image ships it.
  private static final String IMAGE =
      System.getProperty("existdb.docker.image", "existdb/existdb:7.0.0-beta3");
  private static final String APP_PATH = "/exist/apps/existdb-openapi";

  private static final GenericContainer<?> EXIST =
      new GenericContainer<>(DockerImageName.parse(IMAGE))
          .withExposedPorts(8080)
          .waitingFor(Wait.forHttp(APP_PATH + "/api/langservice/capabilities")
              .forStatusCode(200)
              .withStartupTimeout(Duration.ofMinutes(3)));

  @BeforeAll
  static void startContainer() {
    assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
        "Docker not available to Testcontainers; skipping integration test.");
    EXIST.start();
  }

  @AfterAll
  static void stopContainer() {
    if (EXIST.isRunning()) {
      EXIST.stop();
    }
  }

  private ExistClient client() {
    String base = "http://" + EXIST.getHost() + ":" + EXIST.getMappedPort(8080) + APP_PATH;
    return new ExistClient(new ConnectionProfile("it", base, "admin", ""));
  }

  @Test
  void browsesAndRoundTripsAndQueries() throws Exception {
    ExistClient client = client();

    assertEquals("admin", client.whoamiUser());

    List<ExistClient.ChildEntry> root = client.listChildren("/db");
    assertTrue(root.stream().anyMatch(e -> e.collection() && "apps".equals(e.name())),
        "/db should contain the apps collection");

    String path = "/db/oxygen-plugin-it.xq";
    client.putResource(path, "xquery version \"3.1\";\n(: IT marker :) 1 + 1", "application/xquery");
    try {
      assertTrue(new String(client.readResource(path).bytes(), StandardCharsets.UTF_8)
          .contains("IT marker"));
      assertTrue(client.listChildren("/db").stream()
          .anyMatch(e -> !e.collection() && "oxygen-plugin-it.xq".equals(e.name())));

      ExistClient.QueryHandle handle = client.runQuery("(1 to 5)", null);
      assertEquals(5, handle.items());
      String page = client.fetchResultsRaw(handle.cursor(), 1, 10, "adaptive");
      assertTrue(page.contains("\"3\""));
      client.closeCursor(handle.cursor());
    } finally {
      client.runQuery("xmldb:remove('/db', 'oxygen-plugin-it.xq')", null);
    }
  }
}
