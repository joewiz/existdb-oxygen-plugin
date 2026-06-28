# Trio CI image

The `integration` job in `ci.yml` boots a **pinned trio image** instead of a published eXist release, because the plugin relies on trio-era existdb-openapi behavior not yet in any release (e.g. create-on-PUT, the structured `/api/query` error envelope, multipart `.xar` install). This file is the audit trail for that pin: what it is, what it guarantees, when to bump it, and how to rebuild it.

## What "the trio" is

The plugin sits downstream of three eXist-db projects that evolve together: **eXist core** (the database), **existdb-openapi** (the HTTP/JSON API the plugin speaks to — its durable contract), and **eXide** (the web IDE that's prior art and a sibling consumer of the same API). Many of the capabilities the plugin needs are still in-flight as open PRs spread across those repos, not yet in any single release.

"The trio" is the **integration build that merges those in-flight PRs into one running stack** — eXist core + existdb-openapi + eXide (plus supporting XARs like roaster, and the vector/ONNX extension) — so the plugin can be developed and tested against the combined, not-yet-released behavior. It's maintained as a moving target by a dedicated build process (the `rebuild-trio` workflow) and normally runs locally on `:19110`; this CI image is a **pinned snapshot** of it. As each constituent PR lands in a published release, the corresponding delta drops out of the trio (see the merge-down checklist below), and eventually the pin can return to a stock published image.

## The pin

```
ghcr.io/joewiz/existdb-trio@sha256:f7d3854045ed96766d4332229e33559b046a61b2e103ac7eccf91d260d21a131
```

- Human tag: `ghcr.io/joewiz/existdb-trio:2026-06-27` (CI pins the digest, not the tag). Supersedes `sha256:427fa2dd…` (2026-06-26), which added one delta: existdb-openapi **#72**.
- Multi-arch OCI index: `linux/amd64` (CI runners) + `linux/arm64` (Apple-silicon local runs).
- **Public** on GHCR — runners pull anonymously, no login, no Docker Hub rate limit.
- Size: ~364 MiB compressed per arch.

Set in `ci.yml` as `TRIO_IMAGE` and passed to the IT via `-Dexistdb.docker.image=$TRIO_IMAGE`. `ExistClientIT` caps the container at **4 GiB** (`withMemory`) — required, because the image's JVM runs ZGC with `-XX:MaxRAMPercentage=75`, so the heap is sized to the cgroup limit; with no limit it would size to ~75% of the runner's full RAM and risk an OOM-kill (working set is ~2.6 GiB).

## Behaviors this image guarantees (what the IT/plugin depends on)

- `PUT /api/db/resource?path=…` **creates** a new resource → 201.
- `POST /api/query` returns the **#71 structured error envelope** (`code` / `message` / user-relative `line`/`column` / `raw`); query errors are HTTP **400**.
- **Multipart** `POST /api/packages/install` (the `file` part).
- `GET /api/db/collection/export?format=zip|xar`.
- **#72 `/api/db` listing resilience** — an unreadable child degrades to an entry flagged `accessible: false` instead of 500ing the whole collection; every child carries an `accessible` boolean.
- Serialization params on reads (`indent`, `omit-xml-declaration`, `exist.expand-xincludes`).
- **ft:fields** present (`#6455`/`#6459`) — load-bearing for the entire `/api/*` surface (the openapi app statically imports it).
- Vector/KNN search (ONNX `all-MiniLM-L6-v2`, 384-dim).

## Provenance

**eXist base** — integration worktree `trio-rebuild-2026-06-21`, HEAD `61a28c3dd2`, off `origin/develop` `eb02e363bb`:

| PR | Head SHA | Role |
|----|----------|------|
| #6455 + #6459 | `357ae9fb3d` | ft:search-scope / ft:fields — load-bearing for all of `/api/*` |
| #6491 + #6493 | `b680220c3e` | map-key `op:same-key` conformance + compression serialization-options |
| #6492 | `869edb7a8b` | file:sync expand-xincludes via options map |
| joewiz/exist#15 | `f04baa14a6` | instance-wide allow-guest-access clamp |
| #6497 | `5b3ac8a1f2` | binary raw PUT / xmldb:store under XML mime |
| #6506 | `33d7cded21` | BinaryValueFromFile shared-reference refcount |
| #6507 | `c0d301d47f` | `Sequence.containsReference` recursion — the multipart-store fix |
| vector ext | — | vector module + ONNX `all-MiniLM-L6-v2`, built `-Ponnx-model` |

**existdb-openapi** — worktree `collection-export`, branch `trio-openapi-export-search`, HEAD `44ac992`:

| Feature | PR | SHA |
|---------|----|-----|
| db-core + collection export (zip/xar) | #59 + #68 | `7512021` |
| search field-scope | #58 | `5f29f3c` |
| vector "Similar to" search | #60 | `5b5c1a2` |
| query external-variables | #61 | `68452db` |
| multipart `.xar` upload | #49 | `099e1628f8` |
| /api/query structured error envelope | #71 | cherry-pick `19dae74` |
| /api/db listing resilience + `accessible` flag | #72 | cherry-pick `ccd9fa4` → `44ac992` |

**Other XARs:** roaster `joewiz/roaster#fix/map-key-response-code-string` (baked `roaster-1.12.1.xar`); eXide #824 db-core adapter (`eXide-4.0.1.xar`).

**Deliberately excluded:** eXist-db/exist#6508 (resource-naming contract) — unratified (pending eXist-db/exist#6463) and would regress the plugin's awkward-name round-trip, so it would make CI red by construction. A naming-inclusive image + awkward-name canary is a separate, later effort.

**Not yet included (harmless, just not needed yet):** existdb-openapi #69 (`packages:list` resilience for a package poisoned with a literal `${app.version}`). No current IT or feature exercises a poison package on the bed, so it wasn't part of this bump — `GET /api/packages` on this image still 500s if such a package is installed. Fold it into a future bump if the bed needs that resilience (flagged by api-strategy, 2026-06-27).

## Merge-down checklist (drop each delta from the pin as it lands in a release)

Each item below is a reason the pin is needed; when its PRs ship in a published eXist/existdb-openapi the CI image should be rebuilt without that delta, and eventually the pin can return to a stock published image:

- [ ] existdb-openapi #49 (multipart install), #58, #59/#68 (export), #60 (vector), #61 (ext-vars), **#71 (error envelope)**, **#72 (db-listing resilience + `accessible` flag)** released.
- [ ] eXist core #6455/#6459 (ft:fields), #6491, #6492, #6493, #6497, #6506, **#6507 (multipart store)** released.
- [ ] Once all of the above are in a published image, switch `TRIO_IMAGE` back to that release and retire this file.

## Rebuild recipe (to bump the pin)

The eXist core is unchanged from the `:19110` trio (`multipart-fix-beta`); this image = that core's `docker-dir` with the openapi XAR swapped for the #71-carrying build.

1. Build the openapi XAR: in `~/workspace/existdb-openapi/.claude/worktrees/collection-export` (branch `trio-openapi-export-search`), `JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -o -DskipTests package` → `target/existdb-openapi-0.9.7.xar`.
2. Copy it over `…/exist-docker/target/exist-docker-7.0.0-SNAPSHOT-docker-dir/autodeploy/existdb-openapi-0.9.7.xar` (eXide#824 + fixed roaster already staged). Rebuild the eXist core only if a core PR changed.
3. Build + push multi-arch, then read the new index digest:
   ```
   gh auth token | docker login ghcr.io -u joewiz --password-stdin
   docker buildx build --builder multiarch --platform linux/amd64,linux/arm64 \
     --provenance=false --sbom=false \
     -t ghcr.io/joewiz/existdb-trio:<YYYY-MM-DD> --push <docker-dir>
   docker buildx imagetools inspect ghcr.io/joewiz/existdb-trio:<YYYY-MM-DD>
   ```
4. Update `TRIO_IMAGE` in `ci.yml` and the pin here to the new `@sha256:…` index digest.

(Source: rebuild-trio's reports `joe-vault/Claude/existdb-oxygen-plugin/2026-06-26-report-trio-ghcr-image-published.md` and `2026-06-27-report-trio-image-bump-72.md`.)

## Org transfer

When this repo moves to the `eXist-db` org, move the image + pin to an org-owned GHCR package (`ghcr.io/eXist-db/…`).
