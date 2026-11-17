#!/usr/bin/env bash
#
# Headless smoke test for the HTTP password/chat encryption and the
# developer-password-as-second-factor behaviour.
#
# Usage:
#   ./scripts/security-smoke.sh [path/to/hdsl-web.jar]
#
# The script starts a throwaway panel on 127.0.0.1 with HDSL_HTTPS=false and
# HDSL_DEVELOPER_GUARD=all, then runs web/scripts/security-smoke.mjs:
#   - encrypted login
#   - encrypted developer-password set + elevate
#   - gated ACME settings read after elevation
#   - chat-key handshake
#   - encrypted GET and POST /api/chat/*
#
# It uses web/node_modules for @noble; run `(cd web && npm install)` first.
# Nothing outside a temporary /tmp directory and the chosen port is touched.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="${1:-${JAR:-}}"
if [[ -z "$JAR" ]]; then
  JAR="$(ls -1 "$ROOT"/build/libs/hdsl-web-*.jar 2>/dev/null | head -n 1 || true)"
fi
if [[ -z "$JAR" || ! -f "$JAR" ]]; then
  echo "error: no hdsl-web jar found; run ./gradlew shadowJar or pass a jar path" >&2
  exit 2
fi
if [[ ! -d "$ROOT/web/node_modules/@noble" ]]; then
  echo "error: web/node_modules/@noble is missing; run (cd web && npm install)" >&2
  exit 2
fi

PORT="${HDSL_SMOKE_PORT:-39090}"
HTTPS="${HDSL_SMOKE_HTTPS:-false}"
SCHEME="http"
if [[ "$HTTPS" == "true" ]]; then
  SCHEME="https"
fi
DATA_DIR="$(mktemp -d "${TMPDIR:-/tmp}/hdsl-security-smoke.XXXXXX")"
LOG="$DATA_DIR/server.log"
PID=""

cleanup() {
  if [[ -n "$PID" ]] && kill -0 "$PID" 2>/dev/null; then
    kill "$PID" 2>/dev/null || true
    for _ in $(seq 1 20); do
      kill -0 "$PID" 2>/dev/null || break
      sleep 0.2
    done
    kill -9 "$PID" 2>/dev/null || true
  fi
  rm -rf "$DATA_DIR"
}
trap cleanup EXIT

echo "jar:      $JAR"
echo "data dir: $DATA_DIR"
echo "port:     $PORT"

HDSL_DATA="$DATA_DIR" \
HDSL_PORT="$PORT" \
HDSL_BIND_HOST=127.0.0.1 \
HDSL_ADMIN_PASSWORD='smoke-admin-pass' \
HDSL_HTTPS=false \
HDSL_DEVELOPER_GUARD=all \
HDSL_HTTPS="$HTTPS" \
setsid java -jar "$JAR" >"$LOG" 2>&1 < /dev/null &
PID=$!

BASE="$SCHEME://127.0.0.1:$PORT"
for _ in $(seq 1 60); do
  if curl -kfsS --max-time 2 "$BASE/api/health" >/dev/null 2>&1; then
    break
  fi
  if ! kill -0 "$PID" 2>/dev/null; then
    echo "server exited before becoming healthy" >&2
    tail -40 "$LOG" >&2
    exit 1
  fi
  sleep 1
done
if ! curl -kfsS --max-time 2 "$BASE/api/health" >/dev/null 2>&1; then
  echo "server did not become healthy" >&2
  tail -40 "$LOG" >&2
  exit 1
fi

cd "$ROOT/web"
if [[ "$HTTPS" == "true" ]]; then
  NODE_TLS_REJECT_UNAUTHORIZED=0 node scripts/security-smoke.mjs "$BASE" 'smoke-admin-pass' 'smoke-dev-pass'
else
  node scripts/security-smoke.mjs "$BASE" 'smoke-admin-pass' 'smoke-dev-pass'
fi
echo "security smoke: PASS"
