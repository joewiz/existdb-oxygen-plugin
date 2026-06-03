# existdb-oxygen-plugin

An [Oxygen XML Editor](https://www.oxygenxml.com/) plugin that integrates with [eXist-db](https://exist-db.org/) 7.0+ via the [existdb-openapi](https://github.com/eXist-db/existdb-openapi) HTTP API. See the tasking in `joe-vault/Claude/existdb-oxygen-plugin/` for project history, motivation, and roadmap.

## Project context

- **Runs inside Oxygen.** Loaded by Oxygen at editor startup, in Oxygen's own bundled JVM.
- **Talks to eXist over HTTP.** The plugin does not link against eXist's Java API; it speaks to a running eXist 7.0+ instance through the existdb-openapi endpoints (`/exist/apps/existdb-openapi/api/*`). This is the durable contract — surviving future eXist API churn is the whole point.
- **Repo home.** Currently developed at `joewiz/existdb-oxygen-plugin`; to be transferred to the `eXist-db` org (alongside [existdb-openapi](https://github.com/eXist-db/existdb-openapi), [existdb-langserver](https://github.com/eXist-db/existdb-langserver), [eXide](https://github.com/eXist-db/eXide)) once it has proven out.
- **License:** LGPL 2.1 (eXist-db org default).

## JDK target — why bytecode 17, built with JDK 21

Two different JDKs are in play; don't conflate them:

- **Bytecode target (`maven.compiler.release` = 17).** The plugin loads inside *Oxygen's* bundled JVM. `addon.xml` declares `oxy_version 26.0+`, and **Oxygen 26 and 27 bundle Java 17** (28 bundles 21). Bytecode 17 loads on 17 *and* 21; bytecode 21 would throw `UnsupportedClassVersionError` on Oxygen 26/27. So 17 is the support floor — the oldest supported Oxygen's JVM. We use records, text blocks, switch expressions, pattern `instanceof` — all available in 17, so targeting 17 costs nothing.
- **Build toolchain (JDK 21).** The Oxygen 28.x SDK jars are Java-21 bytecode, so the *compiler* must be JDK 21 to read them — it still emits release-17 output. Build with `JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn …`.
- **eXist's Java 21 is irrelevant** to our target: eXist is a separate process reached over HTTP; the plugin never shares its JVM. Only Oxygen's bundled JVM constrains our bytecode.

To drop Oxygen 26/27 support later, bump `oxy_version` to `28.0+` and `maven.compiler.release` to 21.

## Java conventions (the org standard — apply on every change)

The eXist-db org has converged on modern Java idioms. **These are not optional — reviewers will flag each one.** Catch them at the write site, not after the PR is open:

### Forbidden / always-rewrite

- **No string constructors.** `new String("foo")` → `"foo"`. `new String(bytes)` → `new String(bytes, StandardCharsets.UTF_8)` (always specify the charset).
- **No fully-qualified class names in method bodies.** Add an `import` instead.
- **No `Paths.get(...)`.** Use `Path.of(...)`.
- **No `new URI("...")`.** Use `URI.create("...")`.
- **No string-literal charset names.** `"UTF-8"` → `StandardCharsets.UTF_8`.
- **No raw types.** `List` → `List<String>`.
- **No `e.printStackTrace()` in production code.** Log via SLF4J / the project logger.

### Preferred Java idioms

- **Text blocks for any ≥ 2-line string** (XQuery snippets, JSON, XML, error messages).
- **Switch expressions with arrow syntax** for any switch with ≥ 3 cases; comma-separated and pattern labels where applicable.
- **Pattern `instanceof`** over cast-after-check.
- **`var` for locals** when the RHS makes the type obvious.
- **Records** for small immutable data carriers over hand-written boilerplate (e.g. `ExistClient.ChildEntry`, `ResourceContent`, `QueryHandle`).
- **Static imports** for `Files.xxx`, JUnit assertions, etc., where they de-noise call sites.

### NPath / cyclomatic complexity

PMD's `NPathComplexity` runs via Codacy on every PR (threshold 200). Don't increase NPath when modifying a method; for new methods stay under the threshold by extracting helpers. **Never proactively `@SuppressWarnings("PMD.NPathComplexity")`** — let the reviewer decide between a justified suppression and a refactor.

## Tooling

- **Maven build**, single-module. `JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn clean package` → `target/existdb-oxygen-plugin-<version>-plugin.jar` + a filtered `target/addon.xml`. `oxygen.sdk.version` in the pom must match the installed Oxygen (Help → About).
- **Codacy.** PMD primary (OpenGrep for security), ruleset = org defaults; `CODACY_API_TOKEN` per the org convention. Run `codacy-cli analyze --tool pmd <changed files>` locally before every commit — see `~/.claude/CLAUDE.md` for the personal-rule wording.
- **License headers** via `license-maven-plugin` (LGPL 2.1, mirroring eXist-db/exist); `mvn verify` enforces them, `mvn license:format` applies them.

## Testing

Three layers, by how Oxygen-coupled the code is:

- **Unit (no Oxygen, no eXist).** The bulk of the logic — `ExistClient` (HTTP/JSON), `ConnectionProfile`, the `exist:` `URLConnection` — has zero Oxygen dependencies and is tested with JUnit 5 against an in-process existdb-openapi mock (`MockExistServer`, built on the JDK `HttpServer`). Run: `mvn test`. Keep Oxygen-coupled code (the Workspace Access extension, views, `ProfileStore`) as thin adapters so the core stays unit-testable.
- **Integration (real eXist 7 + existdb-openapi).** `ExistClientIT` drives `ExistClient` against the published Docker image via Testcontainers. Run: `mvn verify -Pit` (requires a Docker daemon; skips gracefully where Testcontainers can't reach Docker). This is what catches existdb-openapi shape drift pre-1.0.
- **In-Oxygen UI.** The Oxygen SDK is `provided`-scope and proprietary, with no embeddable/headless workspace and no first-class Workspace-Access test harness — so the in-Oxygen layer (views, tree, `exist:` registration) is verified by a **manual smoke checklist** per release (connect / browse / open / save / run-XQuery). Optionally, `ExistdbBrowserPanel` can be driven headless with AssertJ-Swing over a Mockito-mocked `StandalonePluginWorkspace`.

Always run `mvn test` (and `mvn verify -Pit` if any IT was touched) before opening a PR — not just "it compiles."

## Local test environment (eXist 7 + existdb-openapi)

Use the published Docker image plus `xst` to install dependencies. Do **not** autodeploy XARs into a source-built `exist-distribution-dir` — the autodeploy directory the running server consults isn't always where it looks, dependency ordering matters, and the org convention (see `feedback_xar_install_xst.md` in Joe's memory) is "boot the server, then `xst package install`."

```bash
# 1. Start eXist 7 (the beta image already ships existdb-openapi).
docker run -d --name existdb-7 -p 8080:8080 -p 8443:8443 existdb/existdb:7.0.0-beta3
until curl -sf http://localhost:8080/exist/ > /dev/null; do sleep 2; done

# 2. Tell xst which server to use via a .env file (xst walks up to find it; copy .env.example).
#    EXISTDB_SERVER / EXISTDB_USER / EXISTDB_PASS. .env is gitignored.

# 3. (If a XAR isn't already in the image) install roaster first, then existdb-openapi.
xst package install roaster-*.xar
xst package install existdb-openapi-*.xar

# 4. Smoke test
curl -s http://localhost:8080/exist/apps/existdb-openapi/api/system/info
```

If another eXist already holds 8080/8443, remap the container's host ports (e.g. `-p 18080:8080 -p 18443:8443`) and point the plugin's connection profile there — port is a config concern, not a code concern.

**Testing defaults:** Base URL `http://localhost:8080/exist/apps/existdb-openapi`, admin / empty password, ports 8080/8443. Same base URL as existdb-openapi's Cypress suite (`baseUrl` in its `cypress.config.cjs`).

## Git & PR workflow

- **Base branch:** `main` (trunk-based; version tags mark releases).
- **Commit labels** per CONTRIBUTING.md: `[bugfix]`, `[feature]`, `[refactor]`, `[optimize]`, `[test]`, `[doc]`, `[ci]`, `[ignore]`.
- **PR descriptions:** Summary, What Changed (per file/category), Test Plan checklist, related issues.
- **Two approvals required, no self-merge** (eXist-db org policy).
- **Worktrees:** `~/workspace/existdb-oxygen-plugin/.claude/worktrees/<branch>/` for feature work; keep the primary checkout on `main`.

## CI

GitHub Actions (`.github/workflows/ci.yml`):

- **build + unit** — matrix on **JDK 21 toolchain (Temurin), bytecode target 17**, across ubuntu / macos / windows (the plugin ships cross-platform and Swing / URL-handler behavior varies by OS). Runs `mvn -B verify`.
- **integration** — ubuntu only, `mvn -B verify -Pit` (Testcontainers boots eXist 7 on the runner's native Docker socket).
- The Oxygen SDK resolves from `oxygenxml.com/maven` without auth.
- **release** (`release.yml`, on a `v*` tag): builds, attaches the plugin jar + `addon.xml` to a GitHub Release, and publishes the add-on update site to GitHub Pages so Oxygen's *Help → Install new add-ons* can consume it.

## Coordination

- **existdb-openapi changes affect us.** Watch its tracker for breaking changes pre-1.0; bump the pinned version and re-run integration tests on each release.
- **Oxygen plugin SDK docs:** https://www.oxygenxml.com/doc/versions/28.1/ug-editor/dev_guide/plugins.html
- **eXide** is prior art for eXist-native editor features; vscode-existdb and the eXist Notebook are sibling references.

## Out of scope

- Patching Oxygen's *built-in* eXist integration.
- Replicating eXide's full feature surface. Pick high-value features, ship them well, stop.
- LSP wire protocol. existdb-openapi uses HTTP/JSON shapes inspired by LSP semantics but isn't LSP. If Oxygen's SDK gains a first-class LSP client, that's a separate track via [existdb-langserver](https://github.com/eXist-db/existdb-langserver).
