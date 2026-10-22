#!/usr/bin/env bash
# Publish a text-only WeChat Moment ("Momente" / 朋友圈) end-to-end through the
# MCP server embedded in the Mobile Agent app (android-remote-control-mcp).
#
# WeChat exposes no accessibility nodes for its main UI or the Moment composer,
# so every step is a coordinate tap and the text is injected via the clipboard:
#
#   HOME → open WeChat → Discover → Momente → long-press camera (text composer)
#   → set clipboard → long-press field → "Einfügen" → Veröffentlichen
#
# Screenshots verify each phase with pixel checks (PIL). Nothing is published
# unless the publish button turns green; --dry-run stops right before that.
#
# Usage:
#   scripts/wechat-moment.sh "Hello from MCP 🤖"
#   scripts/wechat-moment.sh --token-from-dai "Hello from MCP 🤖"
#   scripts/wechat-moment.sh --dry-run "draft only, do not publish"
#   MCP_TOKEN=<64-hex> scripts/wechat-moment.sh "…"
#
# Requires: adb (device unlocked, USB debugging), curl, python3 + Pillow.
# Optional: --token-from-dai reads the token dai stored for its Mobile pairing
# (docker + the dev Postgres); otherwise pass MCP_TOKEN.
#
# Env overrides (defaults target a 1080x2412 portrait phone, German UI):
#   MCP_BASE_URL, MCP_TOKEN, MCP_TOKEN_FILE, HOST_PORT, DEVICE_PORT,
#   ADB, PG_CONTAINER, PG_USER, PG_DB, DAI_CRED_KEY,
#   TAB_DISCOVER_X/Y, TAB_WECHAT_X/Y, MOMENTS_ROW_X/Y, CAMERA_X/Y,
#   FIELD_X/Y, PASTE_X/Y, PUBLISH_X/Y, DIALOG_OK_X/Y, OUT_DIR
set -euo pipefail

ADB="${ADB:-adb}"
if ! command -v "$ADB" >/dev/null 2>&1; then
  for c in "$HOME/android-sdk/platform-tools/adb" "$HOME/Android/Sdk/platform-tools/adb" \
           "/usr/lib/android-sdk/platform-tools/adb"; do
    [ -x "$c" ] && { ADB="$c"; break; }
  done
fi
HOST_PORT="${HOST_PORT:-8080}"
DEVICE_PORT="${DEVICE_PORT:-8080}"
BASE="${MCP_BASE_URL:-http://127.0.0.1:${HOST_PORT}}"
WECHAT_PKG="${WECHAT_PKG:-com.tencent.mm}"
OUT_DIR="${OUT_DIR:-/tmp/wechat-moment}"
SETTLE="${SETTLE:-1}"

TAB_WECHAT_X="${TAB_WECHAT_X:-111}";   TAB_WECHAT_Y="${TAB_WECHAT_Y:-2300}"
TAB_DISCOVER_X="${TAB_DISCOVER_X:-675}"; TAB_DISCOVER_Y="${TAB_DISCOVER_Y:-2300}"
MOMENTS_ROW_X="${MOMENTS_ROW_X:-540}"; MOMENTS_ROW_Y="${MOMENTS_ROW_Y:-300}"
CAMERA_X="${CAMERA_X:-991}";           CAMERA_Y="${CAMERA_Y:-161}"
FIELD_X="${FIELD_X:-540}";             FIELD_Y="${FIELD_Y:-300}"
PASTE_X="${PASTE_X:-141}";             PASTE_Y="${PASTE_Y:-224}"
PUBLISH_X="${PUBLISH_X:-870}";         PUBLISH_Y="${PUBLISH_Y:-160}"
DIALOG_OK_X="${DIALOG_OK_X:-545}";     DIALOG_OK_Y="${DIALOG_OK_Y:-2174}"

DRY_RUN=0
TOKEN_FROM_DAI=0
TEXT=""

usage() { sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }
say()  { printf '\n== %s ==\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
die()  { printf '  \033[31m✗\033[0m %s\n' "$*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || die "missing required tool: $1"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY_RUN=1 ;;
    --token-from-dai) TOKEN_FROM_DAI=1 ;;
    --text) shift; TEXT="${1:-}" ;;
    -h|--help) usage 0 ;;
    -*) die "unknown option: $1" ;;
    *) [ -z "$TEXT" ] && TEXT="$1" || TEXT="$TEXT $1" ;;
  esac
  shift
done
[ -n "${TEXT// /}" ] || usage 2

need "$ADB"; need curl; need python3
python3 -c 'import PIL' >/dev/null 2>&1 || die "python3 Pillow is required (pip install pillow)"
mkdir -p "$OUT_DIR"

# ── token ────────────────────────────────────────────────────────────────────
if [ -n "${MCP_TOKEN:-}" ]; then
  TOKEN="$MCP_TOKEN"
elif [ -n "${MCP_TOKEN_FILE:-}" ]; then
  TOKEN="$(tr -d '\r\n' < "$MCP_TOKEN_FILE")"
elif [ "$TOKEN_FROM_DAI" = 1 ]; then
  need docker; need node
  BLOB="$(docker exec "${PG_CONTAINER:-deploy-postgres-1}" \
            psql -U "${PG_USER:-dai}" -d "${PG_DB:-dai}" -tAc \
            "select encrypted_token from dai_credentials where provider='mobile' limit 1" 2>/dev/null || true)"
  [ -n "$BLOB" ] || die "--token-from-dai: no mobile credential in the dai database"
  TOKEN="$(BLOB="$BLOB" DAI_CRED_KEY="${DAI_CRED_KEY:-local-dev-cred-key}" node -e '
const {createDecipheriv,createHash}=require("node:crypto");
const k=createHash("sha256").update(process.env.DAI_CRED_KEY).digest();
const b=Buffer.from(process.env.BLOB,"base64");
const d=createDecipheriv("aes-256-gcm",k,b.subarray(0,12));
d.setAuthTag(b.subarray(12,28));
process.stdout.write(JSON.parse(Buffer.concat([d.update(b.subarray(28)),d.final()]).toString()).token);
')"
  [ -n "$TOKEN" ] || die "--token-from-dai: could not decrypt the token"
else
  die "MCP_TOKEN (or --token-from-dai) is required"
fi

# ── mcp helpers ──────────────────────────────────────────────────────────────
tool() {
  local name="$1" args="$2"
  curl -sS --max-time 30 -X POST "${BASE}/mcp" \
    -H "authorization: Bearer ${TOKEN}" -H 'content-type: application/json' \
    --data-binary "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"${name}\",\"arguments\":${args}}}" \
  | python3 -c '
import json,sys
d=json.load(sys.stdin)
if "error" in d:
    sys.exit("mcp error: " + json.dumps(d["error"]))
c=(d.get("result") or {}).get("content") or []
print(next((b.get("text","") for b in c if b.get("type")=="text"), ""))
'
}
tap()        { tool android_tap        "{\"x\":$1,\"y\":$2}" >/dev/null; }
long_press() { tool android_long_press "{\"x\":$1,\"y\":$2,\"duration\":${3:-900}}" >/dev/null; }
shot()       { "$ADB" exec-out screencap -p > "$1"; }

# ── pixel checks ─────────────────────────────────────────────────────────────
mean_brightness() { # <png>
  python3 - "$1" <<'PY'
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert("RGB")
px = [im.getpixel((x, y)) for x in range(80, 1000, 40) for y in range(220, 700, 40)]
print(round(sum(sum(p) for p in px) / (3 * len(px)), 1))
PY
}
composer_open() { [ "$(mean_brightness "$1" | cut -d. -f1)" -gt 235 ] && echo 1 || echo 0; }
publish_enabled() { # <png> → 1 when the top-right button is WeChat green
  python3 - "$1" <<'PY'
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert("RGB")
# the button's top strip (above the label): green #07c160 when enabled, #f2f2f2 when not
px = [im.getpixel((x, y)) for x in range(750, 950, 10) for y in range(130, 141, 5)]
r = sum(p[0] for p in px) / len(px)
g = sum(p[1] for p in px) / len(px)
b = sum(p[2] for p in px) / len(px)
print(1 if g > 150 and r < 80 and b < 140 else 0)
PY
}
focus_contains() { "$ADB" shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus | grep -q "$1"; }

# ── device ───────────────────────────────────────────────────────────────────
say "device"
"$ADB" get-state >/dev/null 2>&1 || die "no adb device attached/authorized"
"$ADB" forward "tcp:${HOST_PORT}" "tcp:${DEVICE_PORT}" >/dev/null
ok "adb forward tcp:${HOST_PORT} → device:${DEVICE_PORT}"
curl -fsS --max-time 3 "${BASE}/health" >/dev/null || die "MCP server not reachable at ${BASE} (start it in the app)"
ok "MCP server healthy at ${BASE}"

if "$ADB" shell dumpsys window 2>/dev/null | grep -q "mDreamingLockscreen=true"; then
  die "device is locked — unlock it (accessibility cannot read/tap apps behind the keyguard)"
fi

# ── navigate to Momente ──────────────────────────────────────────────────────
say "open WeChat → Discover → Momente"
tool android_press_home '{}' >/dev/null || true
tool android_open_app "{\"package_id\":\"${WECHAT_PKG}\"}" >/dev/null
sleep 2
# Pop any open conversation back to the tab root; BACK on the root backgrounds
# WeChat, and reopening restores the chat list.
for _ in 1 2 3; do tool android_press_back '{}' >/dev/null 2>&1 || true; sleep 0.4; done
tool android_open_app "{\"package_id\":\"${WECHAT_PKG}\"}" >/dev/null
sleep 2
tap "$TAB_WECHAT_X" "$TAB_WECHAT_Y"; sleep "$SETTLE"
tap "$TAB_DISCOVER_X" "$TAB_DISCOVER_Y"; sleep "$SETTLE"
tap "$MOMENTS_ROW_X" "$MOMENTS_ROW_Y"; sleep 2
shot "${OUT_DIR}/1-moments.png"
ok "Moments feed screenshot: ${OUT_DIR}/1-moments.png"

# ── composer ─────────────────────────────────────────────────────────────────
say "open the text composer"
for attempt in 1 2 3; do
  long_press "$CAMERA_X" "$CAMERA_Y" 1000; sleep 1
  shot "${OUT_DIR}/2-composer.png"
  [ "$(composer_open "${OUT_DIR}/2-composer.png")" = 1 ] && break
  # First use shows a one-time "internal test function" notice (dark screen
  # with a green OK) on top of the composer — dismiss it and re-check.
  if [ "$(mean_brightness "${OUT_DIR}/2-composer.png" | cut -d. -f1)" -lt 130 ]; then
    tap "$DIALOG_OK_X" "$DIALOG_OK_Y"; sleep 1
    shot "${OUT_DIR}/2-composer.png"
    [ "$(composer_open "${OUT_DIR}/2-composer.png")" = 1 ] && break
  fi
done
[ "$(composer_open "${OUT_DIR}/2-composer.png")" = 1 ] || die "text composer did not open (see ${OUT_DIR}/2-composer.png)"
ok "composer open"

# ── inject text via the clipboard ────────────────────────────────────────────
say "paste text"
tool android_set_clipboard "$(python3 - "$TEXT" <<'PY'
import json,sys
print(json.dumps({"text": sys.argv[1]}))
PY
)" >/dev/null
long_press "$FIELD_X" "$FIELD_Y" 900; sleep 1
tap "$PASTE_X" "$PASTE_Y"; sleep 1
shot "${OUT_DIR}/3-ready.png"
[ "$(publish_enabled "${OUT_DIR}/3-ready.png")" = 1 ] || die "text did not land in the composer / publish stayed disabled (see ${OUT_DIR}/3-ready.png)"
ok "draft ready: ${TEXT}"

if [ "$DRY_RUN" = 1 ]; then
  say "dry run"
  ok "not published — draft is open on the phone (${OUT_DIR}/3-ready.png)"
  exit 0
fi

# ── publish ──────────────────────────────────────────────────────────────────
say "publish"
tap "$PUBLISH_X" "$PUBLISH_Y"
sleep 4
shot "${OUT_DIR}/4-published.png"
if focus_contains "ImproveSnsTimelineUI"; then
  ok "published — Moments timeline is foreground"
elif [ "$(publish_enabled "${OUT_DIR}/4-published.png")" = 0 ]; then
  ok "published — composer closed (verify ${OUT_DIR}/4-published.png)"
else
  die "publish tap did not take effect (see ${OUT_DIR}/4-published.png)"
fi
ok "screenshot: ${OUT_DIR}/4-published.png"
