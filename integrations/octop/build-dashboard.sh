#!/usr/bin/env bash
# Build the patched Octop dashboard that ships in the panel jar.
#
#   ./integrations/octop/build-dashboard.sh 1.0.3b1
#
# Needs Node 20+ (the panel image has Node 22), npm and network access to
# codeload.github.com. Output:
#   src/main/resources/octop/octop-dashboard-<version>.zip
set -euo pipefail

VERSION="${1:?usage: build-dashboard.sh <octop-version>}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="${OCTOP_BUILD_DIR:-$(mktemp -d)}"
SOURCE="${WORK}/Octop-${VERSION}"
PATCH="${ROOT}/integrations/octop/patches/0001-runtime-base-and-token-namespace.patch"
OUT="${ROOT}/src/main/resources/octop/octop-dashboard-${VERSION}.zip"

mkdir -p "${ROOT}/src/main/resources/octop"
if [ ! -d "${SOURCE}" ]; then
  echo "[octop] downloading Octop ${VERSION}"
  mkdir -p "${WORK}"
  curl -fsSL "https://codeload.github.com/TencentCloud/Octop/zip/refs/tags/v${VERSION}" \
    | tar -xz -C "${WORK}"
fi

echo "[octop] applying dashboard patch"
git -C "${SOURCE}" apply --whitespace=nowarn "${PATCH}" 2>/dev/null \
  || (cd "${SOURCE}" && patch -p1 < "${PATCH}")

echo "[octop] installing dashboard dependencies"
cd "${SOURCE}/dashboard"
npm ci --no-audit --no-fund

echo "[octop] building dashboard (relative base)"
VITE_BASE_PATH=./ NODE_OPTIONS="--max-old-space-size=2048" npm run build:docker

echo "[octop] packing ${OUT}"
rm -f "${OUT}"
(cd "${SOURCE}/src/octop" && zip -qr "${OUT}" dashboard)
echo "[octop] done: ${OUT}"
