# eXist-db Oxygen plugin

An [Oxygen XML Editor](https://www.oxygenxml.com/) plugin that connects to [eXist-db](https://exist-db.org/) 7 over HTTP through [existdb-openapi](https://github.com/eXist-db/existdb-openapi).

**Status:** `0.1.0` released (P0 MVP); P1 language services implemented on the `feature/p1-language-services` branch and being consolidated for a PR. See the [Roadmap](#roadmap).

## Why

This is a community-maintained plugin that connects Oxygen to eXist-db 7 purely over HTTP/JSON via existdb-openapi. Talking to a stable HTTP surface instead of eXist's internal Java APIs keeps the integration resilient to eXist API changes and lets it ship on its own cadence, independent of either product's release schedule.

## Features (MVP)

- **Connect** to an eXist-db server (base URL + credentials), over HTTP or HTTPS (with an optional "trust self-signed/untrusted certificate" toggle for dev servers), stored in Oxygen's options with the password protected using [Oxygen's built-in `UtilAccess.encrypt`](https://www.oxygenxml.com/InstData/Editor/SDK/javadoc/ro/sync/exml/workspace/api/util/UtilAccess.html).
- **Browse** collections and resources in a lazily-loaded tree in the **eXist-db** side view.
- **Open / save** resources directly against the database via the `exist:` URL scheme — Oxygen's normal Save writes straight back to eXist.
- **Run XQuery** from the **eXist-db → Run XQuery…** menu and view paged results.
- **Run Current Editor** — execute the active editor's XQuery (or its selection) against eXist with one click (eXist-db menu, editor right-click, or toolbar button); results open in a new editor *and* as a navigable list in the Results view, where double-clicking a stored-node result jumps to its source document. No transformation scenario to set up — though the **eXist-db (HTTP)** engine also works as a native XQuery transformation scenario if you prefer.

## Running XQuery in Oxygen — engine behavior & quirks

There are two ways to run a query through the plugin, and they behave differently because Oxygen drives them through different parts of its API. Knowing which to reach for — and a couple of XQuery/Oxygen quirks — saves confusion.

**Two entry points.** The plugin registers an **eXist-db (HTTP)** XQuery engine, so it appears anywhere Oxygen lets you pick a transformer:

- **XPath/XQuery Builder pane** (Window → Show View → XPath/XQuery Builder). Pick the **eXist-db (HTTP)** engine, type an expression, and run. Results land in Oxygen's native **Results** view — the same guided, browsable list you get from *Find All* or a built-in XPath search (a row per result with a snippet). This is the right tool for *exploring* a document or the database.
- **Run Current Editor** (eXist-db menu / editor right-click / toolbar). Runs the whole active editor (or its selection), opens the serialized result sequence in a **new editor tab**, *and* lists one navigable row per result in an **eXist-db XQuery Results** tab in the Results view. Double-clicking a row jumps to that result: for a stored database node it opens the node's **source document** and selects the element; for any other result (atomic values like `1 to 10`, computed/in-memory nodes) it selects the item in the serialized-output editor. This is the right tool for *running a script* and getting its full output while still being able to walk the results.

**Context item.** The XPath/XQuery Builder evaluates against the document you have open: the plugin sends that document to eXist as the query's *context item*, so context-dependent expressions like `//title` or `.//section` work against the open file (this needs eXist with [existdb-openapi PR #41](https://github.com/eXist-db/existdb-openapi/pull/41) — older servers ignore the field and such expressions simply return nothing). **Run Current Editor sets no context item** — there the editor *is* the query, so a script-level `//title` would query the whole database, not a single document. If you want a query scoped to one document from the editor, open it explicitly in the query (e.g. `doc('/db/.../file.xml')//title`).

**Namespace gotcha — `//para` vs `//*:para`.** An unprefixed name test like `//para` matches only elements in *no namespace*. Many real documents put their elements in a default namespace — DocBook 5 uses `http://docbook.org/ns/docbook`, TEI uses `http://www.tei-c.org/ns/1.0`, etc. — so `//para` against such a file returns **nothing**, even though the document is full of `<para>` elements. Use a wildcard-namespace test, `//*:para`, or declare and use a prefix in the query. This is standard XQuery behavior, not a plugin bug, but it surprises people constantly.

**Result sequences open as one document.** When Run Current Editor returns a sequence of more than one XML node, the plugin wraps the items in a neutral `<results>…</results>` container before opening them — a sequence of several top-level elements is not a well-formed XML document on its own (XML allows a single root), and without the wrapper Oxygen would report a "markup following the root element must be well-formed" parse error over otherwise-valid results. Single-node results open as-is; non-XML (atomic) results open as plain text.

**Jumping to a result's source node.** From the **eXist-db XQuery Results** tab (Run Current Editor), double-clicking a row that came from a stored database node opens that node's source document and selects the originating element — in both Text and Author mode. This uses each result's `documentURI` + `nodeId` (from [existdb-openapi #43](https://github.com/eXist-db/existdb-openapi/pull/43), closing #40): the plugin resolves the node's canonical `fn:path()` XPath on demand and locates it in the opened document. It needs a server with #43; without it, those rows fall back to selecting the value in the serialized-output editor. (Oxygen's own **XPath/XQuery Builder** uses its built-in Results integration, which doesn't expose this stored-node jump — for source navigation, prefer Run Current Editor.)

## Editor actions (XQuery)

Right-click in an XQuery editor (or use the keyboard shortcuts) for the eXist-db language-service actions. They run against the editor's own `exist://` server, or the default server for a local file, so connect first via the eXist-db view. Shortcuts are shown for macOS; on Windows/Linux substitute Ctrl for ⌘ and Alt for ⌥. The actions appear in the order you typically reach for them while writing a query:

| Action | Shortcut | What it does |
|---|---|---|
| **Content Completion (eXist-db)** | ⌥⌘/ | eXist-aware function/variable proposals from the connected server, scoped to what you've typed (a filter field narrows the list further). Accepting a function inserts its call with the argument placeholders, the first one selected to type over. (macOS reserves ⌃Space for input-source switching, so ⌥⌘/ is the reliable trigger.) |
| **Parameter Hints (eXist-db)** | ⇧⌘Space | Shows the enclosing call's signature with the **active parameter** highlighted; also pops automatically as you type `(` and `,`, and follows the caret as you fill in arguments. |
| **Hover Documentation (eXist-db)** | F1 | Documentation for the symbol under the caret — a function's signature + description, or a variable's inferred type (LSP `textDocument/hover`). Overrides Oxygen's default F1 help while an XQuery editor is focused. |
| **Go to Definition (eXist-db)** | — | Jumps to where the symbol under the caret is defined (opening its source document if needed). |
| **Evaluate Query (eXist-db)** | ⌘↩ | Runs the active editor (or its selection) and shows the results where you chose in **Configure eXist-db Connections** — the eXist-db Results pane or a new editor. |

The completion, hover (rich Markdown with Parameters/Returns), parameter hints, and variable-type hover need a server with existdb-openapi [#42](https://github.com/eXist-db/existdb-openapi/pull/42), [#44](https://github.com/eXist-db/existdb-openapi/pull/44), and [#45](https://github.com/eXist-db/existdb-openapi/pull/45); they degrade gracefully (show nothing) against older servers.

## Project configuration (`.existdb.json`)

When you work on an eXist app from the **Project** pane (files on disk, not the database), a `.existdb.json` in the app's directory tells the plugin which server the app belongs to and how to build and install it. This is the same `.existdb.json` convention used by [existdb-langserver](https://github.com/eXist-db/existdb-langserver) (and atom-existdb / vscode-existdb) — the plugin reads the established `servers` and `sync` sections and adds a `build` section described below.

The descriptor is discovered by a **closest-ancestor walk**: from the selected file or folder the plugin walks up to the nearest directory containing a `.existdb.json` (bounded by the Oxygen project root), so a project that holds many app repositories side by side resolves each one to its own descriptor.

**Schema, validation, and File > New.** The plugin ships a JSON Schema for `.existdb.json` (`frameworks/existdb/schema/existdb-json.schema.json`) and offers an **eXist-db Project Descriptor** starter under **File > New → eXist-db**, which includes a `$schema` reference so Oxygen validates it and offers content-completion. To get the same validation on an existing `.existdb.json`, add the `$schema` line pointing at that schema. The framework's `catalog.xml` maps that `$schema` URL to the bundled schema copy, so validation resolves **offline** — Oxygen never has to fetch the schema over the network. (Oxygen associates a JSON Schema via the in-file `$schema` keyword; it has no plugin-frameworkable way to bind a JSON Schema purely by filename — see [Oxygen SDK gaps](#oxygen-sdk-gaps-upstream-wishlist).)

```json
{
  "servers": {
    "localhost": {
      "server": "http://localhost:8080/exist",
      "user": "admin",
      "password": "",
      "root": "/db/apps/my-app"
    }
  },
  "sync": {
    "server": "localhost",
    "root": "/db/apps/my-app",
    "onSave": true,
    "ignore": [".git/**", "node_modules/**", "build/**"]
  },
  "build": {
    "tool": "ant",
    "command": "ant xar",
    "artifact": "build/*.xar"
  }
}
```

- **`servers`** / **`sync`** — the standard sections. `sync.server` names which `servers` entry to use; `sync.root` is the target collection; `sync.ignore` is a list of globs to skip. The plugin uses these for **Upload to eXist-db…** (Project-pane context menu) and, when `sync.onSave` is `true`, for **upload-on-save** (each saved file is mirrored to its mapped collection). The `server` URL is the eXist *servlet-context* root (e.g. `http://localhost:8080/exist`), and is matched to one of your saved connections (eXist-db pane → gear) so credentials come from there.

- **`build`** *(plugin extension to the convention)* — how to build the app. All keys are optional:
  - **`tool`** — `ant`, `maven`, `npm`, `gulp`, or `custom`.
  - **`command`** — the exact shell command (defaults from `tool`: `ant`, `mvn package`, `npm run build`, `gulp`).
  - **`artifact`** — a glob locating the produced `.xar` (defaults to the most recently built `.xar` under the directory).

  When there is no `build` section (or no `.existdb.json` at all), the plugin **auto-detects** the tool from a build marker: `build.xml` → Ant, `pom.xml` → Maven, `package.json` → npm, `gulpfile.js` → gulp. So an existing eXist app with a standard `build.xml` builds with no configuration.

  **Build-time parameters (e.g. a version).** Many eXist apps take the version as a build argument — the standard app `build.xml` substitutes `${app.version}` into `expath-pkg.xml`, and release tooling passes it (e.g. semantic-release runs `ant -Dapp.version=…`). Since `command` is a full shell command, just include the argument there for local builds: `"command": "ant -Dapp.version=1.0.0-dev"`. (Without it, a local `ant` leaves `${app.version}` unexpanded and produces an `.xar` that won't install cleanly.) Real release versions still come from your CI/release tooling; the `command` value is only for local Build / Build & Install.

### Build & install

The Project-pane context menu offers **Build** and **Build & Install**:

- **Build** runs the resolved command and streams its output to the **eXist-db Build** console. Commands run through your **login shell** (`$SHELL -l -i -c`), so tools installed via Homebrew, asdf, nvm, etc. resolve exactly as they do in your terminal — no need to put them on Oxygen's PATH or hardcode paths.
- **Build & Install** then installs the freshly built `.xar` on the target server with [`xst`](https://github.com/eXist-db/xst) (`xst package install`), over eXist's existing REST. The first time (before you trust the project) a dialog shows the build command and lets you pick/override the **target connection** (defaulting to the resolved one). Credentials always come from your saved connection and are passed to `xst` through the environment, never on the command line. (`xst` must be installed — `npm install --global @existdb/xst`.)

Because running a project-defined command executes code on your machine, the first build per project shows a **trust prompt** with the exact command and directory; "Don't ask again for this project" remembers your choice (after which Build & Install is one click, to the resolved connection).

### How the install connection is resolved

Several files can name a server, so the plugin resolves the install target by a single, predictable rule and always lets you see and override it:

1. **Closest-ancestor walk.** From the selected item, the plugin walks up (bounded by the project root) and uses the **first directory** that names a server, via either:
   - **`.existdb.json`** — `sync.server` → the matching `servers` entry (the convention's primary descriptor), or
   - **`.env`** — its `EXISTDB_SERVER` value (the [`xst`](https://github.com/eXist-db/xst) / [node-exist](https://github.com/eXist-db/node-exist) connection convention).
2. **If a directory has both, `.existdb.json` wins** — it is the richer, plugin-native descriptor (it also carries the sync target and build section); `.env` is connection-only.
3. **Credentials never come from the dotfile.** The resolved server URL is matched to one of your **saved connections** (eXist-db pane → gear), and that connection's stored (encrypted) credentials are used — the plugin never reads a password out of a `.env` or `.existdb.json`.
4. **Fallbacks.** If no file names a server, or none matches a saved connection, the **default connection** is used. The Build & Install dialog shows the resolved connection and lets you change it, so the choice is always visible.

(Browsing, opening, and saving in the eXist-db pane are unaffected by these files — they always use the saved connection named in the `exist://` URL.)

## Roadmap

Notional development goals, roughly by priority vs. effort. **P0 is the current MVP** (the Features above); P1–P4 are planned and may change.

**P0 — edit XQuery/XML against an eXist DB** ✅ *implemented*

- Browse collections in a tree view
- Open stored resources
- Save back (with permissions)
- Execute XQuery with paged results

**P1 — language services** (via `/api/langservice/*`) 🚧 *implemented on `feature/p1-language-services`, pending PR*

- **Diagnostics** — native "eXist-db (HTTP)" XQuery validation engine (eXist-accurate; knows `util:`/`xmldb:` and resolves DB module imports), with squiggles + Problems view. Resources opened over `exist:` are also **auto-validated** into the Problems view (on open and after each edit) without selecting the engine.
- **Go-to-definition**, **code completions**, **hover** — eXist-db menu / editor right-click actions (Oxygen reserves native Ctrl-Space and mouse-hover for XQuery, so these are explicit actions; completions filters to the typed prefix).

> **Depends on existdb-openapi [PR #30](https://github.com/eXist-db/existdb-openapi/pull/30)** (a `langservice` `line`/`column` type fix): go-to-definition, hover, and completions need it deployed. Diagnostics works without it.
>
> **Deferred (after P1 merges):** an Oxygen *framework* to auto-default `exist:` XQuery to our validation engine and to explore hooking *native* completion/hover. Server-side [issue #31](https://github.com/eXist-db/existdb-openapi/issues/31): completions returns the full function library unscoped to the cursor prefix.

**P2 — common editor affordances**

- Multiple saved server connection profiles
- **eXist-db side-pane refinements** — right-size the pane's minimum width; drag-and-drop to move resources; right-click actions (Rename, Duplicate, Download, and Oxygen-native ones like Validate); a server-selection dropdown and a settings menu, modeled on Oxygen's Project pane and eXide's Collections pane
- Database-wide resource search (`/api/search`)
- Visual diff between local and stored versions
- Permissions/ownership editing (`/api/db/permissions`)

**P3 — power-user features**

- Function-documentation tooltips
- Module discovery panel (`/api/modules`)
- Package manager UI — install/update/remove (`/api/packages`)

**P4 — Oxygen-specific integration**

- Validate stored XML resources against schemas Oxygen already knows
- Treat eXist collections as Oxygen "remote project" workspaces

Out of scope: replacing Oxygen's built-in eXist integration, and the LSP wire protocol (existdb-openapi is REST with LSP-inspired shapes).

## Oxygen SDK gaps (upstream wishlist)

Limitations in Oxygen's plugin SDK (28.1) that forced a workaround here, or that block a feature outright. If Oxygen addresses any of these, the noted feature gets simpler or becomes possible — worth raising with the Oxygen team. (API claims verified against `oxygen.jar` 28.1 via `javap`.)

- **Open a specific Preferences page programmatically.** No public API (e.g. `PluginWorkspace.showPreferencesPages(String[])`). A plugin *can* contribute a page via `OptionPagePluginExtension` (`<extension type="OptionPage">`), with per-key Global/Project scope via `getProjectLevelOptionKeys()` — but nothing can open it from our own UI. So we keep the query/result defaults in our own "Configure eXist-db Connections" dialog (reachable from the pane's gear) rather than Preferences; moving them to Preferences would lose that one-click access. *Unlocks:* relocating prefs to the native Preferences facility (with project/`.xpr` scoping) while still linking from the gear.
- **Set an editor's display name independent of its URL.** No API to set a tab/Dock title or tooltip. We encode the server name into the `exist://<name-slug>/…` id so titles read meaningfully (and keep old slugs as aliases on rename). *Unlocks:* dropping the name-slug indirection and showing a friendly server label directly.
- **Read the configured editor font.** No public API to get Oxygen's editor/text font (Preferences → Fonts); it's not exposed on the workspace and isn't even persisted in the options until the user customizes it. So the eXist-db Build console reads the font live off the active editor's text component instead (falling back to monospaced when no text editor is open). *Unlocks:* custom views/consoles matching the editor font directly, regardless of whether an editor is open.
- **Associate a JSON Schema by file name from a framework.** XML document types bind a schema by namespace/root/filename, but a JSON document type can't drive JSON-Schema validation from a shipped framework purely by filename: the JSON validation machinery lives in `frameworks/json/json.jar` (not on the classpath), and Oxygen's own JSON-schema associations are done by patching the built-in JSON framework (`extensionPatch`/`poPatch`). So `.existdb.json` validation relies on the in-file `$schema` keyword instead of a filename-only association — the file must carry a `$schema` line. *Partial workaround:* a framework `catalog.xml` **does** get consulted when Oxygen resolves a JSON `$schema` URI, even for `.json` files owned by the built-in JSON framework — so we map the `$schema` URL to the bundled schema and validation works offline. (Earlier we believed framework catalogs only applied to a framework's own documents; that's wrong for `$schema` URI resolution.) The remaining gap is the *filename-only* binding (no `$schema` line in the file). *Unlocks:* shipping a "files named X validate against schema Y" JSON association the way XML document types can.
- **Contribute to native XQuery content-completion and hover.** Oxygen owns content completion and mouse-hover for XQuery and only consults an external `ExternalContentCompletionProvider` when it has *no* proposals of its own, with no hook to merge ours into native Ctrl+Space / hover. So our eXist-aware completion, hover, and parameter hints are explicit actions (⌥⌘/, F1, ⇧⌘Space) instead of the native affordances. *Unlocks:* eXist proposals/docs appearing in Oxygen's own completion popup and hover tooltip.
- **Override an editor accelerator cleanly.** Rebinding F1 (Oxygen's global Help accelerator) to our Hover Documentation required a `KeyEventDispatcher`; a component input-map binding loses to the global menu accelerator. *Unlocks:* per-editor shortcut overrides without a global key dispatcher.
- **Alternating-row striping in `ApplicationTable`.** The factory table paints stripes only behind rows, not the empty area below the last row, so a custom view that wants full-height striping must paint its own (as the results view does). *Unlocks:* native-looking striped lists without custom painting.
- **Hook into the XPath/XQuery Builder's result pipeline.** Beyond supplying the validation/transformation engine, there's no public hook to customize how the Builder renders or navigates results (e.g. stored-node jump-to-source) — which is why "Run Current Editor" uses its own Results-view integration. *Unlocks:* richer result navigation in the Builder's own pane.
- **Register a Data Source Explorer driver / connection type.** None of the SDK's plugin extension types (verified against the full `*PluginExtension` set in `oxygen.jar` and the [Types of Plugin Extensions](https://www.oxygenxml.com/doc/ug-oxygen/topics/pluginTypes.html) list) lets a plugin add a driver, a database/native-XML connection type, or a node to the **Data Source Explorer**. The built-in driver list (Generic JDBC, eXist, MarkLogic, XQJ, …) is internal; a user can only add a *Generic JDBC* driver (JARs + driver class). The sole data-source API, `WorkspaceUtilities.getDataSourceAccess()`, is **read-only**: it lists/reads connections the user already configured (`getAvailableDataSourceConnectionInfos()`, `getDataSourceConnectionInfo(name)`; `DataSourceConnectionInfo` is an immutable property bag) with no register/add method. This is why we ship our own **eXist-db** tree view over existdb-openapi HTTP rather than appearing as a native driver — and why Oxygen's broken legacy XML:DB "eXist" data source can't be fixed from a plugin. *Unlocks:* a first-class eXist node in the Data Source Explorer (and, separately, importing an existing eXist data-source connection to pre-fill one of our profiles — the one thing the read-only API would already allow).
- **Headless/embeddable Workspace-Access harness for tests.** The SDK has no embeddable workspace, so the in-Oxygen UI (views, tree, `exist:` registration) is verified by a manual smoke checklist rather than CI. *Unlocks:* automated UI-layer regression tests.

## Requirements

- Oxygen XML Editor **26.0+** (developed against 28.1, which bundles a Java 21 runtime).
- An eXist-db 7 instance with the **existdb-openapi** app installed (provides `/exist/apps/existdb-openapi/api/*`).

## Build

The build reads the Oxygen SDK jars, which are compiled for the JVM Oxygen ships, so build with a **JDK 21** toolchain (the compiler still targets bytecode 17 for portability):

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn clean package
```

This produces `target/existdb-oxygen-plugin-<version>-plugin.jar` plus a filtered `target/addon.xml`.

The `com.oxygenxml:oxygen-sdk` version in `pom.xml` (`oxygen.sdk.version`) should match your installed Oxygen version (see **Help → About**).

## Install into Oxygen

**As an add-on (recommended):** in Oxygen, **Help → Install new add-ons…**, add this update site URL, then select the eXist-db plugin and follow the wizard:

```
https://joewiz.github.io/existdb-oxygen-plugin/addon.xml
```

Oxygen will offer updates from the same URL as new releases are published. (The update site is an `addon.xml` published to GitHub Pages by the release workflow; the plugin jar itself is a [GitHub Release](https://github.com/joewiz/existdb-oxygen-plugin/releases) asset — nothing is stored in the repo.)

**From a release jar:** download the `*-plugin.jar` from the [latest release](https://github.com/joewiz/existdb-oxygen-plugin/releases), then either install it via **Help → Install new add-ons… → (browse to the jar)** or unzip it into `OXYGEN_INSTALL_DIR/plugins/existdb/` and restart Oxygen.

After installing, open the **eXist-db** view (Window → Show View), click **Connect…**, enter your server details, **Test connection**, then browse.

## Releasing

Releases are **tag-driven via GitHub Actions** — there is no `maven-release-plugin`, and nothing is published to Maven Central. The plugin is distributed as a [GitHub Release](https://github.com/joewiz/existdb-oxygen-plugin/releases) asset plus the GitHub Pages add-on update site. To cut release `X.Y.Z`:

```bash
# 1. Set the release version and commit
mvn versions:set -DnewVersion=X.Y.Z -DgenerateBackupPoms=false
git commit -am "Release X.Y.Z" && git push origin main

# 2. Tag and push — this fires .github/workflows/release.yml
git tag -a vX.Y.Z -m "eXist-db Oxygen plugin X.Y.Z" && git push origin vX.Y.Z

# 3. Bump to the next development version
mvn versions:set -DnewVersion=<next>-SNAPSHOT -DgenerateBackupPoms=false
git commit -am "Begin <next>-SNAPSHOT" && git push origin main
```

The tagged `release.yml` run builds with JDK 21, attaches `*-plugin.jar` + `addon.xml` to a GitHub Release, and deploys the update site to GitHub Pages. One-time prerequisites (already configured): repo **Settings → Pages → Source = GitHub Actions**, and the `github-pages` deployment environment must allow `v*` tags.

## Architecture

| Concern | Where |
|---|---|
| Plugin entry point | `ExistdbPlugin` |
| Workspace view + menu | `ExistdbWorkspaceAccessPluginExtension` |
| `exist:` open/save scheme | `ExistdbURLStreamHandlerPluginExtension`, `protocol/*` |
| HTTP/JSON client | `client/ExistClient` |
| Connection profile + persistence | `model/ConnectionProfile`, `model/ProfileStore` |
| UI (tree, dialogs) | `ui/*` |

The collection tree lists children via `GET /api/db?path=…`, whose response carries a `children` array (sub-collections first, then resources). Resource open/save round-trips through a custom `exist:` URL scheme so Oxygen's native Save writes back to the database.

## License

GNU Lesser General Public License v2.1 or later. See [LICENSE](LICENSE).
