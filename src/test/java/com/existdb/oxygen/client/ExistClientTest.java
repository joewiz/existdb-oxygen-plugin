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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.existdb.oxygen.ExistContext;
import com.existdb.oxygen.model.ConnectionProfile;
import com.existdb.oxygen.protocol.ExistURLStreamHandler;

import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ExistClient} against a canned existdb-openapi mock (no eXist, no network). */
class ExistClientTest {

  private MockExistServer server;
  private ExistClient client;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockExistServer();
    client = new ExistClient(new ConnectionProfile("test", server.baseUrl(), "admin", "secret"));
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  @Test
  void systemInfoReturnsBody() throws Exception {
    assertTrue(client.systemInfo().contains("7.0.0"));
  }

  @Test
  void whoamiUserReadsNestedEffectiveUser() throws Exception {
    // The response nests identity under effective/real; whoamiUser() must dig it out.
    assertEquals("admin", client.whoamiUser());
  }

  @Test
  void listChildrenParsesCollectionChildren() throws Exception {
    List<ExistClient.ChildEntry> children = client.listChildren("/db");
    assertEquals(2, children.size());
    assertTrue(children.get(0).collection());
    assertEquals("apps", children.get(0).name());
    assertEquals("/db/apps", children.get(0).path());
    assertFalse(children.get(1).collection());
    assertEquals("index.xq", children.get(1).name());
  }

  @Test
  void getResourceParsesContentAndMime() throws Exception {
    ExistClient.ResourceContent rc = client.getResource("/db/x.xq");
    assertTrue(rc.content().contains("42"));
    assertEquals("application/xquery", rc.mimeType());
    assertFalse(rc.binary());
  }

  @Test
  void putResourceSendsPathAndContent() throws Exception {
    client.putResource("/db/x.xq", "xquery version \"3.1\"; 99", "application/xquery");
    assertTrue(server.lastPutBody().contains("\"path\""));
    assertTrue(server.lastPutBody().contains("99"));
  }

  @Test
  void deleteResourceAndCollectionSucceed() throws Exception {
    client.deleteResource("/db/x.xq"); // must not throw
    client.deleteCollection("/db/old"); // must not throw
  }

  @Test
  void createCollectionSucceeds() throws Exception {
    client.createCollection("/db/new-coll"); // must not throw
  }

  @Test
  void moveAndCopySendSourceAndParent() throws Exception {
    client.move("/db/a/x.xq", "/db/b");
    assertTrue(server.lastPutBody().contains("\"source\""));
    assertTrue(server.lastPutBody().contains("\"parent\""));
    assertTrue(server.lastPutBody().contains("/db/b"));
    client.copy("/db/a/x.xq", "/db/c");
    assertTrue(server.lastPutBody().contains("/db/c"));
  }

  @Test
  void renameAndDuplicateSendNewName() throws Exception {
    client.rename("/db/a/x.xq", "y.xq");
    assertTrue(server.lastPutBody().contains("\"source\""));
    assertTrue(server.lastPutBody().contains("\"newName\""));
    assertTrue(server.lastPutBody().contains("y.xq"));
    client.duplicate("/db/a/x.xq", "x-copy.xq");
    assertTrue(server.lastPutBody().contains("\"newName\""));
    assertTrue(server.lastPutBody().contains("x-copy.xq"));
  }

  @Test
  void runAndFetchAndCloseQuery() throws Exception {
    ExistClient.QueryHandle handle = client.runQuery("(1 to 3)", null);
    assertEquals("C1", handle.cursor());
    assertEquals(3, handle.items());
    assertTrue(server.lastQueryBody().contains("1 to 3"));

    String page = client.fetchResultsRaw(handle.cursor(), 1, 10, "adaptive");
    assertTrue(page.trim().startsWith("["));
    assertTrue(page.contains("\"3\""));

    client.closeCursor(handle.cursor()); // must not throw
  }

  @Test
  void runQueryIncludesContextItemWhenSupplied() throws Exception {
    client.runQuery("//para", null, "<doc><para>hi</para></doc>");
    assertTrue(server.lastQueryBody().contains("\"context-item\""));
    assertTrue(server.lastQueryBody().contains("<para>hi"));
  }

  @Test
  void runQueryOmitsContextItemWhenBlank() throws Exception {
    client.runQuery("1 to 3", null, "  ");
    assertFalse(server.lastQueryBody().contains("context-item"));
  }

  @Test
  void nodePathBuildsFnPathQueryAndReturnsValue() throws Exception {
    String path = client.nodePath("/db/a/x.xml", "3.9");
    assertTrue(server.lastQueryBody().contains("fn:path"));
    assertTrue(server.lastQueryBody().contains("util:node-by-id"));
    assertTrue(server.lastQueryBody().contains("/db/a/x.xml"));
    assertTrue(server.lastQueryBody().contains("3.9"));
    // The canned cursor yields "1" as the first item's value; nodePath returns it unwrapped.
    assertEquals("1", path);
  }

  @Test
  void searchParsesTotalAndHits() throws Exception {
    ExistClient.SearchResults results = client.search("index", 50);
    assertEquals(7, results.total());
    assertEquals(2, results.hits().size());
    assertEquals("/db/apps/doc/indexing.xml", results.hits().get(0).path());
    assertEquals("range index", results.hits().get(1).snippet());
  }

  @Test
  void searchFieldsParsesKindsAndStringOrArrayAnalyzer() throws Exception {
    ExistClient.SearchFields fields = client.searchFields("/db");
    assertEquals("admin", fields.user());
    assertEquals(List.of("/db"), fields.scope());
    assertEquals(4, fields.fields().size());

    // facet: no type, empty analyzer list, not a plain field
    ExistClient.SearchFieldInfo facet = fields.fields().get(0);
    assertEquals("facet", facet.kind());
    assertEquals("site-app", facet.field());
    assertNull(facet.type());
    assertTrue(facet.analyzers().isEmpty());
    assertFalse(facet.isField());

    // field with analyzer reported as a single string → one-element list
    ExistClient.SearchFieldInfo category = fields.fields().get(1);
    assertTrue(category.isField());
    assertEquals("xs:string", category.type());
    assertTrue(category.returnable());
    assertEquals(1, category.analyzers().size());

    // field with analyzer reported as an array → multi-element list
    ExistClient.SearchFieldInfo content = fields.fields().get(2);
    assertEquals(2, content.analyzers().size());

    // vector kind is preserved
    assertEquals("vector", fields.fields().get(3).kind());
  }

  @Test
  void nonSuccessStatusRaisesExistHttpException() {
    ExistHttpException ex = org.junit.jupiter.api.Assertions.assertThrows(
        ExistHttpException.class, () -> client.getResource("/db/missing.xq"));
    assertEquals(404, ex.getStatusCode());
    assertTrue(ex.getResponseBody().contains("not found"));
  }

  @Test
  void diagnosticsParsesProblems() throws Exception {
    List<ExistClient.Diagnostic> problems = client.diagnostics("1 +", "/db");
    assertEquals(1, problems.size());
    ExistClient.Diagnostic d = problems.get(0);
    assertEquals(0, d.line());
    assertEquals(5, d.column());
    assertEquals(1, d.severity());
    assertEquals("XPST0003", d.code());
    assertTrue(d.message().contains("unexpected"));
    assertTrue(server.lastLangBody().contains("\"expression\""));
    assertTrue(server.lastLangBody().contains("module-load-path"));
  }

  @Test
  void completionsParsesProposals() throws Exception {
    List<ExistClient.Completion> items = client.completions("fn:co", null);
    assertEquals(1, items.size());
    assertEquals("fn:count#1", items.get(0).label());
    assertEquals(3, items.get(0).kind());
    assertEquals("fn:count(${1:\\$arg})", items.get(0).insertText());
    assertTrue(items.get(0).isSnippet());
  }

  @Test
  void hoverSendsPositionAndParsesMarkdownContents() throws Exception {
    ExistClient.Hover h = client.hover("fn:count(1)", 0, 3, null);
    assertTrue(h.contents().contains("fn:count"));
    assertTrue(h.contents().contains("```xquery"));
    assertTrue(server.lastLangBody().contains("\"line\":0"));
    assertTrue(server.lastLangBody().contains("\"column\":3"));
  }

  @Test
  void signatureHelpParsesSignaturesAndActiveParameter() throws Exception {
    ExistClient.SignatureHelp help = client.signatureHelp("fn:count(", 0, 9, null);
    assertEquals(1, help.signatures().size());
    assertEquals(0, help.activeParameter());
    ExistClient.SignatureInfo sig = help.signatures().get(0);
    assertTrue(sig.label().contains("fn:count"));
    assertEquals(1, sig.parameters().size());
    assertEquals("$arg as item()*", sig.parameters().get(0).label());
    assertEquals("The input sequence", sig.parameters().get(0).documentation());
  }

  @Test
  void definitionParsesLocation() throws Exception {
    ExistClient.Definition def = client.definition("local:my-func()", 1, 2, null);
    assertEquals(5, def.line());
    assertEquals("local:my-func#1", def.name());
    assertEquals("/db/apps/myapp/lib.xqm", def.uri());
  }

  @Test
  void existUrlRoundTripReadsAndWrites() throws Exception {
    // The exist: URL handler reads via GET and saves via PUT-on-close (Oxygen's native save path).
    ConnectionProfile profile = new ConnectionProfile("test", server.baseUrl(), "admin", "secret");
    profile.setId("srv");
    ExistContext.setActiveProfile(profile);
    URL url = ExistURLStreamHandler.toUrl("srv", "/db/x.xq");

    String read = new String(url.openConnection().getInputStream().readAllBytes(),
        StandardCharsets.UTF_8);
    assertTrue(read.contains("42"));

    URLConnection writeConn = url.openConnection();
    try (OutputStream os = writeConn.getOutputStream()) {
      os.write("xquery version \"3.1\"; 123".getBytes(StandardCharsets.UTF_8));
    }
    assertTrue(server.lastPutBody().contains("123"));
  }

  @Test
  void listPackagesParsesFields() throws Exception {
    List<ExistClient.PackageInfo> packages = client.listPackages();
    assertEquals(2, packages.size());
    ExistClient.PackageInfo roaster = packages.get(0);
    assertEquals("roaster", roaster.abbrev());
    assertEquals("1.12.1", roaster.version());
    assertEquals("library", roaster.type());
    assertEquals("Roaster", roaster.title());
    assertEquals(List.of("e-editiones"), roaster.authors());
  }

  @Test
  void removePackageReportsDependentsWithoutForce() throws Exception {
    ExistClient.RemoveResult result = client.removePackage("roaster", false);
    assertFalse(result.removed());
    assertEquals(List.of("http://exist-db.org/apps/eXide"), result.dependents());
  }

  @Test
  void removePackageWithForceSucceeds() throws Exception {
    ExistClient.RemoveResult result = client.removePackage("roaster", true);
    assertTrue(result.removed());
    assertTrue(result.dependents().isEmpty());
  }

  @Test
  void removePackageReportsNotFound() throws Exception {
    ExistClient.RemoveResult result = client.removePackage("ghost", false);
    assertFalse(result.removed());
    assertTrue(result.dependents().isEmpty());
    assertTrue(result.message().contains("not found"));
  }

  @Test
  void installPackageSendsNameAndRegistryUrl() throws Exception {
    client.installPackage("http://www.functx.com",
        "https://exist-db.org/exist/apps/public-repo/find", "2.0.0");
    String body = server.lastPackageBody();
    assertTrue(body.contains("\"url\":\"https://exist-db.org/exist/apps/public-repo/find\""));
    assertTrue(body.contains("\"version\":\"2.0.0\""));
  }

  @Test
  void installPackageThrowsWhenServerReportsFailure() {
    // The server replies HTTP 200 with {"success":false,...}; the client must surface that.
    assertThrows(java.io.IOException.class, () ->
        client.installPackage("fail", "https://exist-db.org/exist/apps/public-repo/find", ""));
  }

  @Test
  void parseAvailablePackagesReadsCatalogEntries() throws Exception {
    String appsXml = """
        <apps version="2.2.0">
          <app path="dashboard-2.0.9.xar">
            <name>http://exist-db.org/apps/dashboard</name>
            <title>Dashboard</title>
            <abbrev>dashboard</abbrev>
            <version>2.0.9</version>
            <author>eXist Project</author>
          </app>
          <app path="functx-1.0.1.xar">
            <name>http://www.functx.com</name>
            <title>FunctX</title>
            <abbrev>functx</abbrev>
            <version>1.0.1</version>
          </app>
        </apps>
        """;
    List<ExistClient.AvailablePackage> available = ExistClient.parseAvailablePackages(appsXml);
    assertEquals(2, available.size());
    assertEquals("dashboard", available.get(0).abbrev());
    assertEquals("Dashboard", available.get(0).title());
    assertEquals("2.0.9", available.get(0).version());
    assertEquals("eXist Project", available.get(0).author());
    assertEquals("functx", available.get(1).abbrev());
    assertEquals("", available.get(1).author()); // missing author → empty
  }

  @Test
  void checkPackageUpdatesParsesRegistryAndUpdates() throws Exception {
    ExistClient.UpdateCheck check = client.checkPackageUpdates();
    assertEquals("https://exist-db.org/exist/apps/public-repo", check.registry());
    assertEquals(1, check.updates().size());
    ExistClient.PackageUpdate update = check.updates().get(0);
    assertEquals("functx", update.abbrev());
    assertEquals("1.0.0", update.installed());
    assertEquals("2.0.0", update.available());
  }
}
