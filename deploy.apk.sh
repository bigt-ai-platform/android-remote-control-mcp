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
# Release builds are version-tagged (like ../wallet): before the build, HEAD's
# vX.Y.Z tag is reused if present, else the patch of the highest vX.Y.Z tag is
# bumped and tagged. The tag is pushed after a successful upload. Tagging BEFORE
# the build is what makes `git describe` return the tag, so the APK carries a real
# release versionName instead of a `0.0.0-dev+<hash>` name the OTA updater skips.
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
#   VERSION        override the release tag version (e.g. VERSION=1.2.3)
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
    --help|-h) sed -n '2,32p' "$0"; exit 0 ;;
    *) echo "unknown arg: $a" >&2; exit 1 ;;
  esac
done

S3_ENDPOINT="${S3_ENDPOINT:-https://minio-s1001.bigt.ai}"
S3_BUCKET="${S3_BUCKET:-mobile-agent}"
S3_PREFIX="${S3_PREFIX:-}"
S3_KEY_PREFIX="${S3_PREFIX:+$S3_PREFIX/}"
OUT_DIR="${OUT_DIR:-$ROOT/out}"
MC_IMAGE="${MC_IMAGE:-quay.io/minio/mc}"
# quay.io/minio/mc is often blocked/unavailable; fall back to an image that
# bundles `mc` (the minio server image) or a local registry mirror, exactly as
# ../dai/deploy.apk.sh does.
if ! docker image inspect "$MC_IMAGE" >/dev/null 2>&1; then
  docker pull "$MC_IMAGE" >/dev/null 2>&1 || true
fi
if ! docker image inspect "$MC_IMAGE" >/dev/null 2>&1; then
  for alt in "quay.io/minio/minio:latest" "ghcr.io/bigt-ai-platform/mc:latest" "ghcr.io/bigt-ai-platform/minio:latest"; do
    docker image inspect "$alt" >/dev/null 2>&1 || docker pull "$alt" >/dev/null 2>&1 || true
    if docker image inspect "$alt" >/dev/null 2>&1; then
      echo "mc image $MC_IMAGE unavailable — using $alt (bundles mc)" >&2
      MC_IMAGE="$alt"
      break
    fi
  done
fi
PUBLIC_BASE_URL="${PUBLIC_BASE_URL:-$S3_ENDPOINT/$S3_BUCKET}"
: "${S3_ACCESS_KEY:?set S3_ACCESS_KEY}"
: "${S3_SECRET_KEY:?set S3_SECRET_KEY}"

FLAVOR_CAP="$(echo "${FLAVOR:0:1}" | tr '[:lower:]' '[:upper:]')${FLAVOR:1}"
TYPE_CAP="$(echo "${BUILD_TYPE:0:1}" | tr '[:lower:]' '[:upper:]')${BUILD_TYPE:1}"
SHA="$(git rev-parse --short HEAD)"
OUT_APK="$OUT_DIR/MobileAgent-$FLAVOR-$BUILD_TYPE-$SHA.apk"

# Version tag (release builds only) — mirrors ../wallet: the highest vX.Y.Z tag
# drives the release version. Tagging BEFORE the build is what makes `git describe`
# return the tag, so the APK's versionName is a real release version instead of a
# `0.0.0-dev+<hash>` name that the in-app OTA updater skips as a dev build. The tag
# is pushed only after a successful upload (see the end of this script).
TAG_TO_PUSH=""
if [ "$SKIP_BUILD" -eq 0 ] && [ "$BUILD_TYPE" = release ]; then
  if [ -z "${VERSION:-}" ]; then
    HEAD_TAG="$(git tag --points-at HEAD --list 'v[0-9]*.[0-9]*.[0-9]*' | sort -V | tail -1 || true)"
    if [ -n "$HEAD_TAG" ]; then
      VERSION="${HEAD_TAG#v}"
    else
      CUR="$(git tag --list 'v[0-9]*.[0-9]*.[0-9]*' | sed 's/^v//' | sort -V | tail -1 || true)"
      [ -n "$CUR" ] || CUR="$(grep '^VERSION_NAME=' gradle.properties | cut -d= -f2)"
      [ -n "$CUR" ] || CUR="0.0.0"
      VERSION="$(echo "$CUR" | awk -F. '{printf "%d.%d.%d", $1, $2, $3+1}')"
    fi
  fi
  if git rev-parse -q --verify "refs/tags/v$VERSION" >/dev/null; then
    [ "$(git rev-list -n 1 "v$VERSION")" = "$(git rev-parse HEAD)" ] \
      || { echo "tag v$VERSION already exists on another commit — pass VERSION=<higher>" >&2; exit 1; }
  else
    git tag -a "v$VERSION" -m "android-remote-control-mcp $VERSION"
  fi
  TAG_TO_PUSH="v$VERSION"
  echo "version tag: $TAG_TO_PUSH"
fi

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

if [ -n "$TAG_TO_PUSH" ]; then
  git push origin "$TAG_TO_PUSH" \
    || { echo "release uploaded, but pushing $TAG_TO_PUSH failed — push the tag manually" >&2; exit 1; }
  echo "pushed: $TAG_TO_PUSH"
fi

echo "uploaded: $PUBLIC_BASE_URL/$S3_KEY_PREFIX$NAME"
echo "uploaded: $PUBLIC_BASE_URL/${S3_KEY_PREFIX}$ALIAS"
echo "manifest: $PUBLIC_BASE_URL/$S3_KEY_PREFIX$MANIFEST ($VERSION_NAME / code $VERSION_CODE)"
