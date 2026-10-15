#!/usr/bin/env bash
# Physical-device e2e for the short-code pairing feature (plan 66).
#
# Drives a REAL connected Android device over adb:
#   1. (optional) build + install the debug APK
#   2. start the MCP server headlessly via the ADB service trampoline
#   3. read the pairing code off the running app's UI (uiautomator dump)
#   4. POST /pair/claim and assert: a token comes back, the token authorizes /mcp,
#      and the same code cannot be spent twice
#   5. stop the server
#
# Requires: adb, curl, python3, a connected device, and (unless --skip-build) a
# working Android build toolchain. Defaults target the gms debug build.
#
# Usage:
#   scripts/e2e-pairing-adb.sh                 # assume the APK is already installed
#   scripts/e2e-pairing-adb.sh --build         # ./gradlew :app:installGmsDebug first
#   PKG=<appid> PORT=<device-port> scripts/e2e-pairing-adb.sh
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ADB:-adb}"
PKG="${PKG:-com.danielealbano.androidremotecontrolmcp.gms.debug}"
ACT_MAIN="${PKG}/com.danielealbano.androidremotecontrolmcp.ui.MainActivity"
ACT_TRAMPOLINE="${PKG}/com.danielealbano.androidremotecontrolmcp.services.mcp.AdbServiceTrampolineActivity"
DEVICE_PORT="${PORT:-8080}"
HOST_PORT="${HOST_PORT:-18080}"
HEALTH_TIMEOUT_S="${HEALTH_TIMEOUT_S:-60}"
BUILD=0
[ "${1:-}" = "--build" ] && BUILD=1

FAILED=0
pass() { printf '  \033[32mPASS\033[0m %s\n' "$*"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$*"; FAILED=1; }
info() { printf '\n== %s ==\n' "$*"; }

need() { command -v "$1" >/dev/null 2>&1 || { echo "missing required tool: $1" >&2; exit 2; }; }
need "$ADB"
need curl
need python3

if [ "$BUILD" = 1 ]; then
  info "build + install debug APK"
  ( cd "$ROOT" && ./gradlew :app:installGmsDebug )
fi

info "device"
"$ADB" get-state >/dev/null || { echo "no device/emulator attached" >&2; exit 2; }
"$ADB" shell pm path "$PKG" >/dev/null 2>&1 || {
  echo "package $PKG is not installed (run with --build, or install the debug APK)" >&2
  exit 2
}

info "grant permissions (best effort)"
"$ADB" shell settings put secure enabled_accessibility_services \
  "${PKG}/com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService" || true
"$ADB" shell cmd notification allow_listener \
  "${PKG}/com.danielealbano.androidremotecontrolmcp.services.notifications.McpNotificationListenerService" || true

info "start MCP server"
"$ADB" shell am start -n "$ACT_TRAMPOLINE" --es action start >/dev/null
"$ADB" forward "tcp:${HOST_PORT}" "tcp:${DEVICE_PORT}" >/dev/null
trap '"$ADB" shell am start -n "'"$ACT_TRAMPOLINE"'" --es action stop >/dev/null 2>&1 || true; "$ADB" forward --remove "tcp:'"$HOST_PORT"'" >/dev/null 2>&1 || true' EXIT

BASE="http://127.0.0.1:${HOST_PORT}"

info "wait for /health"
healthy=0
for _ in $(seq 1 "$HEALTH_TIMEOUT_S"); do
  if curl -fsS --max-time 2 "$BASE/health" >/dev/null 2>&1; then healthy=1; break; fi
  sleep 1
done
if [ "$healthy" = 1 ]; then
  pass "server reachable at $BASE"
else
  fail "server did not become healthy within ${HEALTH_TIMEOUT_S}s"
  echo "--- recent app logcat ---"
  "$ADB" logcat -d -t 200 -s MCP:McpServer MCP:McpServiceTrampoline 2>/dev/null | tail -40 || true
  exit 1
fi

info "read pairing code from the app UI"
"$ADB" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
"$ADB" shell wm dismiss-keyguard >/dev/null 2>&1 || true
"$ADB" shell am start -n "$ACT_MAIN" >/dev/null

# The pairing card is below the fold on the Server screen, and the dump can also
# contain 8-char strings that are NOT the code (another app's text, a log line),
# so scroll while accumulating every candidate, then let the claim pick the real
# one instead of trusting the first match.
CANDIDATES=""
DUMP=""
for _ in $(seq 1 6); do
  # Dismiss a system dialog (e.g. the USB-mode prompt) that may cover the app.
  FOCUS="$("$ADB" shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus || true)"
  if ! printf '%s' "$FOCUS" | grep -q "$PKG"; then
    "$ADB" shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
    "$ADB" shell am start -n "$ACT_MAIN" >/dev/null 2>&1 || true
    sleep 2
  fi
  "$ADB" shell uiautomator dump /sdcard/pairing_dump.xml >/dev/null 2>&1 || true
  DUMP="$("$ADB" exec-out cat /sdcard/pairing_dump.xml 2>/dev/null || true)"
  NEW="$(printf '%s' "$DUMP" | grep -oE '\b[A-HJ-NP-Z2-9]{8}\b' || true)"
  CANDIDATES="$(printf '%s\n%s\n' "$CANDIDATES" "$NEW" | sed '/^$/d' | sort -u | head -n 8)"
  # Scroll the Server screen so the pairing card comes into view.
  "$ADB" shell input swipe 540 1800 540 900 300 >/dev/null 2>&1 || true
  sleep 1
done
CANDIDATES="$(printf '%s' "$CANDIDATES" | tr '\n' ' ' | sed 's/ *$//')"
if [ -n "$CANDIDATES" ]; then
  pass "candidate code(s) from the UI: $CANDIDATES"
else
  fail "could not find an 8-char pairing code in the UI dump"
  echo "--- UI text nodes (first 60) ---"
  printf '%s' "$DUMP" | grep -oE 'text="[^"]*"' | head -60 || true
  exit 1
fi

info "claim the code"
CODE=""
CLAIM_STATUS=""
CLAIM_JSON=""
for CAND in $CANDIDATES; do
  CLAIM_BODY="$(curl -sS -w '\n%{http_code}' -X POST "$BASE/pair/claim" \
    -H 'content-type: application/json' -d "{\"code\":\"$CAND\",\"name\":\"e2e\"}")"
  CLAIM_STATUS="$(printf '%s' "$CLAIM_BODY" | tail -n1)"
  CLAIM_JSON="$(printf '%s' "$CLAIM_BODY" | sed '$d')"
  [ "$CLAIM_STATUS" = 200 ] && { CODE="$CAND"; break; }
  # 429 is terminal — stop burning the rate-limit budget on remaining candidates.
  [ "$CLAIM_STATUS" = 429 ] && break
done
if [ "$CLAIM_STATUS" = 200 ]; then
  pass "POST /pair/claim returned 200 (code $CODE)"
else
  fail "POST /pair/claim returned $CLAIM_STATUS: $CLAIM_JSON"
  exit 1
fi
TOKEN="$(printf '%s' "$CLAIM_JSON" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("token",""))')"
DEVICE_ID="$(printf '%s' "$CLAIM_JSON" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("device_id",""))')"
if [ "${#TOKEN}" -eq 64 ]; then
  pass "token issued (64 hex chars), device_id=$DEVICE_ID"
else
  fail "unexpected token: '$TOKEN'"
fi

info "token authorizes /mcp"
MCP_STATUS="$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE/mcp" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'content-type: application/json' \
  -H 'accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"e2e","version":"1"}}}')"
if [ "$MCP_STATUS" != 401 ]; then
  pass "/mcp accepted the paired token (HTTP $MCP_STATUS, not 401)"
else
  fail "/mcp rejected the paired token (401)"
fi

info "bogus token is refused"
BOGUS_STATUS="$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE/mcp" \
  -H 'Authorization: Bearer not-a-real-token' \
  -H 'content-type: application/json' \
  -H 'accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"e2e","version":"1"}}}')"
if [ "$BOGUS_STATUS" = 401 ]; then
  pass "bogus token refused (401)"
else
  fail "bogus token not refused (HTTP $BOGUS_STATUS)"
fi

info "single-use: the same code cannot be spent again"
SECOND_STATUS="$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE/pair/claim" \
  -H 'content-type: application/json' -d "{\"code\":\"$CODE\"}")"
if [ "$SECOND_STATUS" = 404 ]; then
  pass "second claim of the same code returned 404"
else
  fail "second claim returned $SECOND_STATUS (expected 404)"
fi

info "result"
if [ "$FAILED" = 0 ]; then
  printf '\033[32mE2E PASSED\033[0m\n'
else
  printf '\033[31mE2E FAILED\033[0m\n'
fi
exit "$FAILED"
