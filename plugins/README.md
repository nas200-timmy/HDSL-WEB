# First-party plugins

This directory holds the plugins HDSL-web itself ships — as opposed to the
community terminal interfaces the panel lists from npm.

```
plugins/
  README.md            ← this contract
  shared/              ← the terminal toolkit both terminals import
    term/  ui/  i18n.js
  dsh-inproc-tui/      ← in-process terminal, a mode bundle over dsh-base (0.2.x)
    package.json       declares dsh.bundle.patch
    cordis.patch.yml
    src/…
  dsh-acp-tui/         ← ACP terminal, a program the panel starts (0.1.2 and up)
    package.json       bin/dsh-acp-tui.js
    src/…
```

`shared/` is not a package: each tarball carries a copy (`package/shared`), and the
staging step rewrites the import specifiers that point at the repository layout
(`../../shared/…`) into the installed one (`../shared/…`). A plugin's own source
therefore stays readable and its tarball stays self-contained.

## What "first-party" means here

1. **Built from this repository.** The source lives next to the code that ships it,
   under the same licence (GPL-3.0-or-later), reviewed with the panel.
2. **Bundled into the jar.** The build packs each plugin into
   `build/first-party-plugins/<package>-<version>.tgz` and into the jar under
   `first-party-plugins/`, with an `index.json` describing what is there. The panel's
   terminal-plugin install route materialises that tarball and installs it with
   `dsh plugin --profile <profile> add file:<tarball>`, so installing a first-party
   plugin never needs the npm registry.
3. **Offline-buildable.** No dependencies beyond the harness it drives, no build step
   beyond `node --check`. `npm run check` in a plugin directory is the whole build;
   CI runs it in `gradle check`.
4. **Version-tolerant by contract.** A first-party plugin is expected to keep working
   across harness releases, so it talks to the harness through a stable surface (for
   the ACP terminal: the ACP stdio protocol; for the in-process one: a pinned,
   tested 0.2.x range) and degrades when a capability is missing.

## Adding a plugin

1. Create `plugins/<name>/` as a normal npm package. `type: "module"`, a `bin` for a
   program-shaped terminal (see below), no `dependencies`.
2. Add `scripts/check.mjs` that runs offline (syntax check, manifest check), and wire
   `npm run check` to it.
3. Declare the package in `DshTuiPlugins.ENTRIES` if it is a terminal interface, with
   `bundled = true`, its own profile and the harness range it needs.
4. The Gradle build picks the directory up automatically: every directory under
   `plugins/` that has a `package.json` is packed, indexed and added to the jar's
   resources. A plugin without npm on the build machine keeps the previously built
   tarball (the build warns).

## Terminal interfaces specifically

A terminal interface is an **app plugin**, so it must not be installed into the
instance's `web` profile: `dsh-web-app` and a terminal app each parse the same argv and
the pair does not boot. Each catalogue entry therefore names the profile its own
documentation installs into (`tui`, `dsh-tui`, `pi-tui`, `dsh-inproc-tui`,
`dsh-acp-tui`), and the panel starts the terminal on that profile rather than the
instance's.

The two terminals here are **alternatives at run time**: both may be installed (the ACP
one is the fallback for an instance whose dsh version moves), but only the first fitting
one is started. The catalogue gives both the same `runtimeGroup` (`dsh-terminal`), the
list reports which row is `active`, and the page renders the other as standby —
installing it is a deliberate choice, not a mistake to block.

Two shapes are supported, and the panel starts each the way its manifest says:

- **A program (`bin`)** — `dsh-acp-tui`. The panel starts
  `node <profile>/node_modules/<package>/<bin>`, which keeps the terminal (not a
  package-manager shim, not a shell) as the root process of the PTY. The program then
  spawns the harness itself (`dsh --profile acp`), so the panel's web-shell sees one
  process writing ANSI to a terminal, exactly like any other terminal application.
  This shape is what makes it portable to 0.1.x: it only needs the ACP profile
  template.
- **A mode bundle (`dsh.bundle`)** — `dsh-inproc-tui`. The panel installs it and boots
  its profile (`dsh --profile dsh-inproc-tui`), exactly like the community bundles. The
  plugin runs inside the harness process, which is what buys token-level streaming and
  the approval/question channels — at the price of the 0.2.x seam it is written
  against. The catalogue's `requiresDsh` says which lines may run it.

## Checking

```sh
# the plugin's own offline check
node plugins/dsh-acp-tui/scripts/check.mjs

# from the repository root: builds the tarball into build/first-party-plugins/
./gradlew packageFirstPartyPlugins
```
