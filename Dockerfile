# HDSL-web — single-container delivery.
#
# No `# syntax=` directive on purpose: this Dockerfile uses no frontend-specific
# feature, so it builds with the built-in frontend and needs no extra pull.

# Three stages, each with one job:
#
#   web      node:22-bookworm-slim   builds the React panel SPA into /web/dist
#   build    eclipse-temurin:21-jdk  embeds that dist and produces the shadow jar
#   runtime  debian:trixie-slim      full Debian + OpenJDK 21 + Node 22 + pnpm,
#                                    running as the unprivileged user `hdsl`
#
# Build:  docker build -t hdsl-web .
# Run:    docker run -d --name hdsl-web -p 3080:3080 -v hdsl-data:/data hdsl-web
#
# See README.md ("构建与开发", "环境变量") and docs/deployment.md for the
# reverse-proxy, TLS-rotation and backup notes that go with this image.

# =============================================================================
# Stage 1 — web: the panel SPA
# =============================================================================
FROM node:22-bookworm-slim AS web

# Where npm fetches packages from. Point this at a mirror when the network is
# slow, e.g. --build-arg NPM_REGISTRY=https://registry.npmmirror.com/
ARG NPM_REGISTRY=https://registry.npmjs.org/

WORKDIR /web

# package-lock.json is what makes this reproducible, so the two manifests go in
# first: a change under web/src/ does not invalidate the dependency layer.
# .dockerignore keeps web/node_modules and web/dist out of the context.
# The npm cache mount keeps downloaded tarballs inside BuildKit, so a rebuild
# of this layer does not re-fetch the whole dependency set.
COPY web/package.json web/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm \
    npm ci --no-audit --no-fund --registry="${NPM_REGISTRY}"

# Everything else the SPA build reads (sources, index.html, vite/ts configs).
COPY web/ ./
RUN npm run build
# -> /web/dist (base "./", so it mounts at any path the proxy chooses)


# =============================================================================
# Stage 2 — build: the single executable jar
# =============================================================================
# A JDK, not a JRE: the Gradle wrapper compiles gradle/wrapper/GradleWrapperNeo.java
# on demand, which needs javac.
FROM eclipse-temurin:21-jdk AS build

WORKDIR /src

# A release build takes its version from -PreleaseVersion=1.2.3; anything else
# (including empty) is a development build, per build.gradle.kts.
ARG RELEASE_VERSION=

COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY src ./src
RUN chmod +x ./gradlew

# The panel is built in the web stage and dropped in at web/dist. build.gradle.kts
# runs `npm run build` there only when npm is on PATH *and* web/package.json
# exists; this image has neither, so `buildWeb` is skipped and `syncWebDist`
# packs exactly the dist copied here into src/main/resources/web -> the jar.
COPY --from=web /web/dist ./web/dist

# repo1.maven.org measures ~18 KB/s on a China network while the Aliyun mirror
# is ~1 MB/s (60x). The init script prepends the mirror to the repositories the
# build already declares — anything the mirror lacks still falls back to
# Central. Container-only: a developer's local build is untouched.
RUN printf '%s\n' \
      'allprojects {' \
      '    buildscript { repositories { maven { url "https://maven.aliyun.com/repository/public" } } }' \
      '    repositories { maven { url "https://maven.aliyun.com/repository/public" } }' \
      '}' \
      'settingsEvaluated { settings ->' \
      '    settings.pluginManagement { repositories { maven { url "https://maven.aliyun.com/repository/gradle-plugin" } } }' \
      '}' \
      > /tmp/mirror.gradle

# The Gradle home is a BuildKit cache mount, so the wrapper distribution and
# every resolved dependency survive rebuilds — a warm build skips the minutes
# of cold downloads and recompiles only what changed. The first fill is what
# the mirror above accelerates.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --console=plain -I /tmp/mirror.gradle "-PreleaseVersion=${RELEASE_VERSION}" shadowJar
# -> /src/build/libs/hdsl-web-<version>.jar


# =============================================================================
# Stage 3 — runtime: Debian + JDK 21 + Node 22 + pnpm
# =============================================================================
# Debian 13 (trixie) carries openjdk-21-jre-headless as a first-class package
# (21.0.11+10-1~deb13u2), so no backports are involved.
FROM debian:trixie-slim AS runtime

ARG DEBIAN_FRONTEND=noninteractive

# ca-certificates  TLS to npm/registry, plus the JVM's own trust store
# curl             registry downloads and the HEALTHCHECK probe
# tini             PID 1: reaps zombies, forwards SIGTERM to the JVM
# git              plugins installed from a repository (`git:` sources, ls-remote)
# zstd             reading compressed session logs when a pack is exported
# xz-utils         .tar.xz archives (manual/fallback Node runtimes, packs)
# procps, less     so `docker exec -it … ps/less` works while troubleshooting
# openjdk-21-jre-headless  the runtime itself (drags in util-linux -> flock(1),
#                  which the session-lease check shells out to)
ARG NODE_VERSION=22.23.1
ARG PNPM_VERSION=11.28.2
# Node 官方 dist 的下载基址。国内网络直连 nodejs.org 会 TLS 握手失败（curl exit 35），
# 可用 --build-arg NODE_DIST_MIRROR=https://mirrors.cloud.tencent.com/nodejs-release 换镜像；
# 镜像与官方逐字节一致（SHASUMS256 校验不变，仍以官方清单为准）。
ARG NODE_DIST_MIRROR=https://nodejs.org/dist

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates \
      curl \
      git \
      less \
      openjdk-21-jre-headless \
      procps \
      tini \
      xz-utils \
      zstd \
 && rm -rf /var/lib/apt/lists/*

# Node 22 LTS from the official nodejs.org tarball. The architecture is read
# from dpkg, so the same Dockerfile builds on amd64 and arm64; the archive is
# checked against the release's own SHASUMS256.txt before it is unpacked.
RUN set -eux; \
    arch="$(dpkg --print-architecture)"; \
    case "${arch}" in \
      amd64) node_arch=x64 ;; \
      arm64) node_arch=arm64 ;; \
      *) echo "HDSL-web: unsupported architecture ${arch}" >&2; exit 1 ;; \
    esac; \
    tarball="node-v${NODE_VERSION}-linux-${node_arch}.tar.xz"; \
    curl -fsSLo "/tmp/${tarball}" "${NODE_DIST_MIRROR}/v${NODE_VERSION}/${tarball}"; \
    curl -fsSLo /tmp/SHASUMS256.txt "${NODE_DIST_MIRROR}/v${NODE_VERSION}/SHASUMS256.txt"; \
    grep " ${tarball}\$" /tmp/SHASUMS256.txt > /tmp/node.tar.sha256; \
    (cd /tmp && sha256sum -c node.tar.sha256); \
    mkdir -p /opt/node; \
    tar -xJf "/tmp/${tarball}" -C /opt/node --strip-components=1 --no-same-owner; \
    rm -f "/tmp/${tarball}" /tmp/SHASUMS256.txt /tmp/node.tar.sha256; \
    /opt/node/bin/node --version

ENV PATH=/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

# pnpm through corepack, which Node 22 bundles. `corepack enable` puts the
# `pnpm` shim on PATH (that is what HDSL probes and what dsh forwards to), and
# `corepack prepare` seeds a known-good version so the common case needs no
# network. A project that pins an exact version (dsh declares
# `packageManager: pnpm@…`) makes corepack fetch that one on first use; its home
# is under /data, so the download is writable and survives a container
# replacement instead of being re-fetched every time.
ENV COREPACK_HOME=/data/corepack
ENV COREPACK_ENABLE_DOWNLOAD_PROMPT=0

RUN set -eux; \
    if command -v corepack >/dev/null 2>&1; then \
      corepack enable; \
      corepack prepare "pnpm@${PNPM_VERSION}" --activate \
        || corepack install --global "pnpm@${PNPM_VERSION}"; \
    else \
      npm install -g "pnpm@${PNPM_VERSION}"; \
    fi; \
    pnpm --version

# The account everything above runs as. uid/gid 1000 is what a bind-mounted
# host directory usually expects; a named volume inherits the ownership of
# /data as it is in this image (including the corepack cache seeded above).
#
# pnpm 11 does not take `storeDir` from an npm_config_* environment variable —
# only auth and registry settings still come from .npmrc; everything else lives
# in pnpm's global config.yaml. So the store location is written there, both
# here (the default a container gets even if the entrypoint is bypassed) and by
# the entrypoint below (which can also fold in NPM_CONFIG_REGISTRY).
RUN set -eux; \
    groupadd --gid 1000 hdsl; \
    useradd --uid 1000 --gid 1000 --create-home --home-dir /home/hdsl --shell /bin/bash hdsl; \
    mkdir -p /app /data "${COREPACK_HOME}" /home/hdsl/.config/pnpm; \
    printf 'storeDir: %s\n' "/data/pnpm-store" > /home/hdsl/.config/pnpm/config.yaml; \
    chown -R hdsl:hdsl /data /home/hdsl

WORKDIR /app

# The jar, and the licence files that ship with it.
COPY --from=build /src/build/libs/hdsl-web-*.jar /app/hdsl-web.jar
COPY LICENSE NOTICE /app/

# The entrypoint does two things an exec-form CMD cannot:
#
#  1. JAVA_OPTS needs a shell (environment variables are not expanded in the
#     exec form), so the command line is rewritten here;
#  2. pnpm's global config.yaml is regenerated from PNPM_STORE_DIR and
#     NPM_CONFIG_REGISTRY, because pnpm 11 reads settings from that file rather
#     than from the npm_config_* environment variables npm itself honours.
#
# tini stays PID 1, and the script `exec`s the JVM, so signals still reach it.
RUN printf '%s\n' \
      '#!/bin/sh' \
      'set -e' \
      '' \
      '# pnpm 11 reads its settings from the global config file, not from' \
      '# npm_config_* environment variables (only auth and registry still come' \
      '# from .npmrc). Materialise the two settings this image owns, so a dsh' \
      '# install — which pnpm performs — lands in the persisted store and goes' \
      '# to the registry the operator configured.' \
      'config_dir="${XDG_CONFIG_HOME:-${HOME:-/home/hdsl}/.config}/pnpm"' \
      'mkdir -p "$config_dir"' \
      '# Registries are habitually written with a trailing slash (the compose' \
      '# file suggests one), but corepack joins its own "/pnpm/latest" to the' \
      '# value: two slashes, and mirrors answer that with 404. Strip it once,' \
      '# so both readers get the same, working URL.' \
      'registry="${NPM_CONFIG_REGISTRY:-}"' \
      'while [ "${registry%/}" != "$registry" ]; do registry="${registry%/}"; done' \
      '{' \
      '  printf "storeDir: %s\n" "${PNPM_STORE_DIR:-/data/pnpm-store}"' \
      '  if [ -n "$registry" ]; then' \
      '    printf "registry: %s\n" "$registry"' \
      '  fi' \
      '} > "$config_dir/config.yaml"' \
      '' \
      '# Corepack downloads the pnpm binary itself from its own registry' \
      '# setting, which the pnpm config file does not cover. Point corepack' \
      '# at the same mirror when one was configured.' \
      'if [ -n "$registry" ] && [ -z "${COREPACK_NPM_REGISTRY:-}" ]; then' \
      '  COREPACK_NPM_REGISTRY="$registry"' \
      '  export COREPACK_NPM_REGISTRY' \
      'fi' \
      '' \
      '# Word splitting is the point: JAVA_OPTS is a list of JVM options.' \
      'exec java ${JAVA_OPTS:-} "$@"' \
      > /usr/local/bin/hdsl-entrypoint \
 && chmod 0755 /usr/local/bin/hdsl-entrypoint

# --------------------------------------------------------------- runtime env --
# HDSL_DATA       the one directory a deployment persists (see README's table)
# HDSL_PORT       the panel's port; HTTPS, when enabled, speaks TLS on the same port
# NPM_CONFIG_REGISTRY   registry for npm children, and folded into pnpm's
#                       config.yaml by the entrypoint
# PNPM_STORE_DIR  pnpm's shared store, inside the volume so instances hard-link
#                 from it instead of copying (same filesystem)
# LANG            without it the JVM's console encoding is ANSI_X3.4-1968, and
#                 every Chinese log line — the install failures among them —
#                 reaches `docker logs` as ?????. C.UTF-8 is built into Debian's
#                 glibc, so this costs nothing.
ENV HDSL_DATA=/data \
    HDSL_PORT=3080 \
    NPM_CONFIG_REGISTRY=https://registry.npmjs.org/ \
    PNPM_STORE_DIR=/data/pnpm-store \
    LANG=C.UTF-8 \
    HOME=/home/hdsl

VOLUME ["/data"]

# The panel. dsh instances listen on 3081-4081 on loopback only and are never
# published; everything reaches them through this port.
EXPOSE 3080

USER hdsl

# -k because the first boot generates a self-signed certificate, which is a
# perfectly healthy state. Try TLS first, then plain HTTP, so the probe works
# before and after `https.enabled` is flipped.
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -fsSk "https://127.0.0.1:${HDSL_PORT:-3080}/api/health" >/dev/null 2>&1 \
   || curl -fsS "http://127.0.0.1:${HDSL_PORT:-3080}/api/health" >/dev/null 2>&1 \
   || exit 1

ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/hdsl-entrypoint"]
# 入口脚本已经 exec java，CMD 只提供 java 之后的参数（写 "java" 会拼成 `java -Xmx1g java -jar …`）
CMD ["-jar", "/app/hdsl-web.jar"]
