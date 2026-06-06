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
- **Contribute to native XQuery content-completion and hover.** Oxygen owns content completion and mouse-hover for XQuery and only consults an external `ExternalContentCompletionProvider` when it has *no* proposals of its own, with no hook to merge ours into native Ctrl+Space / hover. So our eXist-aware completion, hover, and parameter hints are explicit actions (⌥⌘/, F1, ⇧⌘Space) instead of the native affordances. *Unlocks:* eXist proposals/docs appearing in Oxygen's own completion popup and hover tooltip.
- **Override an editor accelerator cleanly.** Rebinding F1 (Oxygen's global Help accelerator) to our Hover Documentation required a `KeyEventDispatcher`; a component input-map binding loses to the global menu accelerator. *Unlocks:* per-editor shortcut overrides without a global key dispatcher.
- **Alternating-row striping in `ApplicationTable`.** The factory table paints stripes only behind rows, not the empty area below the last row, so a custom view that wants full-height striping must paint its own (as the results view does). *Unlocks:* native-looking striped lists without custom painting.
- **Hook into the XPath/XQuery Builder's result pipeline.** Beyond supplying the validation/transformation engine, there's no public hook to customize how the Builder renders or navigates results (e.g. stored-node jump-to-source) — which is why "Run Current Editor" uses its own Results-view integration. *Unlocks:* richer result navigation in the Builder's own pane.
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
