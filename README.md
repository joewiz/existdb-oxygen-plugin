# eXist-db Oxygen plugin

An [Oxygen XML Editor](https://www.oxygenxml.com/) plugin that connects to [eXist-db](https://exist-db.org/) 7 over HTTP through [existdb-openapi](https://github.com/eXist-db/existdb-openapi).

**Status:** pre-alpha (`0.1.0-SNAPSHOT`). MVP under active development.

## Why

This is a community-maintained plugin that connects Oxygen to eXist-db 7 purely over HTTP/JSON via existdb-openapi. Talking to a stable HTTP surface instead of eXist's internal Java APIs keeps the integration resilient to eXist API changes and lets it ship on its own cadence, independent of either product's release schedule.

## Features (MVP)

- **Connect** to an eXist-db server (base URL + credentials), stored in Oxygen's options with the password protected using [Oxygen's built-in `UtilAccess.encrypt`](https://www.oxygenxml.com/InstData/Editor/SDK/javadoc/ro/sync/exml/workspace/api/util/UtilAccess.html).
- **Browse** collections and resources in a lazily-loaded tree in the **eXist-db** side view.
- **Open / save** resources directly against the database via the `exist:` URL scheme — Oxygen's normal Save writes straight back to eXist.
- **Run XQuery** from the **eXist-db → Run XQuery…** menu and view paged results.

## Roadmap

Notional development goals, roughly by priority vs. effort. **P0 is the current MVP** (the Features above); P1–P4 are planned and may change.

**P0 — edit XQuery/XML against an eXist DB** ✅ *implemented*

- Browse collections in a tree view
- Open stored resources
- Save back (with permissions)
- Execute XQuery with paged results

**P1 — language services** (via `/api/langservice/*`)

- Diagnostics (on save / on idle)
- Code completions
- Hover
- Go-to-definition

**P2 — common editor affordances**

- Multiple saved server connection profiles
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
