#!/bin/bash
# deploy.apk.sh — build the Mobile Agent (android-remote-control-mcp) APK and
# upload it to MinIO, mirroring ../httpsms/deploy.apk.sh and ../wallet/deploy.apk.sh.
#
#   ./deploy.apk.sh                 # release, gms flavor (full app)
#   ./deploy.apk.sh --foss          # release, foss flavor (no Google Play Services)
#   ./deploy.apk.sh --debug         # debug build (auto-signed, sideloadable)
#   ./deploy.apk.sh --gradle-only   # skip the cloudflared/ngrok native cross-compile
#   ./deploy.apk.sh --skip-build    # upload the APK already in out/
#
# Env:
#   S3_ENDPOINT    MinIO endpoint (default https://minio-s1001.bigt.ai)
#   S3_ACCESS_KEY / S3_SECRET_KEY (required)
#   S3_BUCKET      (default mobile-agent — the bucket the app links serve from)
#   S3_PREFIX      object prefix inside the bucket (default empty = bucket root)
#   OUT_DIR        local APK dir (default ./out)
#   MC_IMAGE       minio client image (default quay.io/minio/mc)
#   PUBLIC_BASE_URL public base the OTA manifest's apk url is built from
#   MANDATORY      true to mark this build mandatory in the OTA manifest
#
# Uploads out/MobileAgent-<flavor>-<type>-<sha>.apk plus the stable
# MobileAgent.apk (gms) / MobileAgent-foss.apk alias the docs link to, and
# writes mobile-agent-<flavor>-<type>-latest.json — the OTA manifest an in-app
# updater reads. Releases must be signed (keystore.properties) to install over
# an existing build.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

BUILD_TYPE="release"; FLAVOR="gms"; SKIP_BUILD=0; GRADLE_ONLY=0
for a in "$@"; do
  case "$a" in
    --release) BUILD_TYPE=release ;;
    --debug) BUILD_TYPE=debug ;;
    --foss) FLAVOR=foss ;;
    --gms) FLAVOR=gms ;;
    --gradle-only) GRADLE_ONLY=1 ;;
    --skip-build) SKIP_BUILD=1 ;;
    --help|-h) sed -n '2,24p' "$0"; exit 0 ;;
    *) echo "unknown arg: $a" >&2; exit 1 ;;
  esac
done

S3_ENDPOINT="${S3_ENDPOINT:-https://minio-s1001.bigt.ai}"
S3_BUCKET="${S3_BUCKET:-mobile-agent}"
S3_PREFIX="${S3_PREFIX:-}"
S3_KEY_PREFIX="${S3_PREFIX:+$S3_PREFIX/}"
OUT_DIR="${OUT_DIR:-$ROOT/out}"
MC_IMAGE="${MC_IMAGE:-quay.io/minio/mc}"
PUBLIC_BASE_URL="${PUBLIC_BASE_URL:-$S3_ENDPOINT/$S3_BUCKET}"
: "${S3_ACCESS_KEY:?set S3_ACCESS_KEY}"
: "${S3_SECRET_KEY:?set S3_SECRET_KEY}"

FLAVOR_CAP="$(echo "${FLAVOR:0:1}" | tr '[:lower:]' '[:upper:]')${FLAVOR:1}"
TYPE_CAP="$(echo "${BUILD_TYPE:0:1}" | tr '[:lower:]' '[:upper:]')${BUILD_TYPE:1}"
SHA="$(git rev-parse --short HEAD)"
OUT_APK="$OUT_DIR/MobileAgent-$FLAVOR-$BUILD_TYPE-$SHA.apk"

if [ "$SKIP_BUILD" -eq 0 ]; then
  if [ "$GRADLE_ONLY" -eq 1 ]; then
    ./gradlew ":app:assemble${FLAVOR_CAP}${TYPE_CAP}"
  elif [ "$BUILD_TYPE" = release ]; then
    # Full release build — cross-compiles cloudflared (Go + NDK) and ngrok (Rust + NDK).
    make build-release
  elif [ "$FLAVOR" = foss ]; then
    make build-foss
  else
    make build
  fi
  mkdir -p "$OUT_DIR"
  cp "app/build/outputs/apk/$FLAVOR/$BUILD_TYPE/app-$FLAVOR-$BUILD_TYPE.apk" "$OUT_APK"
fi
[ -f "$OUT_APK" ] || { echo "missing $OUT_APK (run without --skip-build first)" >&2; exit 1; }
sha256sum "$OUT_APK"

# Read the version off the artifact when the SDK is around, so the manifest
# always describes the APK it points at; git is the fallback.
VERSION_NAME="$(git describe --tags --always 2>/dev/null || git rev-parse --short HEAD)"
VERSION_CODE="$(git rev-list --count HEAD)"
AAPT="$(ls "${ANDROID_HOME:-/nonexistent}"/build-tools/*/aapt 2>/dev/null | sort -V | tail -1 || true)"
if [ -n "$AAPT" ]; then
  BADGING="$("$AAPT" dump badging "$OUT_APK")"
  VERSION_NAME="$(printf '%s\n' "$BADGING" | sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p")"
  VERSION_CODE="$(printf '%s\n' "$BADGING" | sed -n "s/^package:.*versionCode='\([^']*\)'.*/\1/p")"
fi
[ -n "$VERSION_NAME" ] && [ -n "$VERSION_CODE" ] || { echo "cannot read version from $OUT_APK" >&2; exit 1; }
echo "version: $VERSION_NAME (code $VERSION_CODE)"

if [ "$BUILD_TYPE" = release ] && [ ! -f keystore.properties ]; then
  echo "warning: no keystore.properties — this release APK is UNSIGNED and will not install" >&2
fi

NAME="$(basename "$OUT_APK")"
ALIAS="MobileAgent.apk"
[ "$FLAVOR" = foss ] && ALIAS="MobileAgent-foss.apk"

# OTA manifest — an in-app updater fetches this straight from the (public)
# bucket and compares versionCode against the installed build.
APK_SHA="$(sha256sum "$OUT_APK" | awk '{print $1}')"
MANIFEST="mobile-agent-$FLAVOR-$BUILD_TYPE-latest.json"
printf '{"versionName":"%s","versionCode":%s,"url":"%s/%s","sha256":"%s","mandatory":%s}\n' \
  "$VERSION_NAME" "$VERSION_CODE" "${PUBLIC_BASE_URL%/}" "$S3_KEY_PREFIX$NAME" "$APK_SHA" "${MANDATORY:-false}" \
  > "$OUT_DIR/$MANIFEST"

docker run --rm \
  -e S3_ENDPOINT="$S3_ENDPOINT" -e S3_ACCESS_KEY="$S3_ACCESS_KEY" -e S3_SECRET_KEY="$S3_SECRET_KEY" \
  -e S3_BUCKET="$S3_BUCKET" -e S3_KEY_PREFIX="$S3_KEY_PREFIX" -e NAME="$NAME" -e ALIAS="$ALIAS" -e MANIFEST="$MANIFEST" \
  -v "$OUT_DIR:/out:ro" --entrypoint /bin/sh "$MC_IMAGE" -c \
  'mc alias set up "$S3_ENDPOINT" "$S3_ACCESS_KEY" "$S3_SECRET_KEY" >/dev/null && \
   mc cp "/out/$NAME" "up/$S3_BUCKET/$S3_KEY_PREFIX$NAME" && \
   mc cp "/out/$NAME" "up/$S3_BUCKET/${S3_KEY_PREFIX}$ALIAS" && \
   mc cp "/out/$MANIFEST" "up/$S3_BUCKET/$S3_KEY_PREFIX$MANIFEST"' \
  || { echo "upload failed — check S3_* creds / MC_IMAGE ($MC_IMAGE)" >&2; exit 1; }

echo "uploaded: $PUBLIC_BASE_URL/$S3_KEY_PREFIX$NAME"
echo "uploaded: $PUBLIC_BASE_URL/${S3_KEY_PREFIX}$ALIAS"
echo "manifest: $PUBLIC_BASE_URL/$S3_KEY_PREFIX$MANIFEST ($VERSION_NAME / code $VERSION_CODE)"
