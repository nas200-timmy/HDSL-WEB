# Octop integration assets

The panel ships one patched file set for Octop's dashboard. The Python side is
the upstream portable archive; only the SPA is rebuilt here.

- `patches/0001-runtime-base-and-token-namespace.patch`
  - reads the mount from `<base>` / `window.__OCTOP_BASE__` at runtime;
  - builds with a relative Vite base;
  - scopes `auth_token`, `auth_remember_pref` and `active-agent` by mount, so
    several instances can share one panel origin.
- `build-dashboard.sh <version>` downloads the tag, applies the patch, builds
  and writes `src/main/resources/octop/octop-dashboard-<version>.zip`.

The jar installer downloads `Octop-portable-<plat>-<version>.zip`, unpacks it,
and replaces `packages/octop/dashboard` with the zip above. Keep the version in
the generated resource name in sync with the version(s) the picker offers.
