#!/bin/bash
# deploy.apk.sh — build the Mobile Agent (android-remote-control-mcp) APK and
# upload it to MinIO, mirroring ../httpsms/deploy.apk.sh and ../wallet/deploy.apk.sh.
#
#   ./deploy.apk.sh                 # release, gms flavor (full app)
#   ./deploy.apk.sh --foss          # release, foss flavor (no Google Play Services)
#   ./deploy.apk.sh --fdroid        # foss release + publish the self-hosted F-Droid repo
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
#   S3_BUCKET      (default apps — the shared release bucket)
#   S3_PREFIX      object prefix inside the bucket (default android-remote-control-mcp/apk)
#   OUT_DIR        local APK dir (default ./out)
#   MC_IMAGE       minio client image (default quay.io/minio/mc)
#   PUBLIC_BASE_URL public base the OTA manifest's apk url is built from
#   MANDATORY      true to mark this build mandatory in the OTA manifest
#   VERSION        override the release tag version (e.g. VERSION=1.2.3)
#   FDROID_DIR     local F-Droid workspace (default $OUT_DIR/fdroid)
#   FDROID_S3_PREFIX bucket prefix for the repo (default android-remote-control-mcp/fdroid)
#   FDROID_KEYSTORE key that signs the repo index (default: the app's release key from keystore.properties)
#   FDROID_VENV    fdroidserver venv (default ~/.cache/android-remote-control-mcp/fdroid-venv)
#
# Uploads out/MobileAgent-<flavor>-<type>-<sha>.apk plus the stable
# MobileAgent.apk (gms) / MobileAgent-foss.apk alias the docs link to, and
# writes mobile-agent-<flavor>-<type>-latest.json — the OTA manifest an in-app
# updater reads. Releases must be signed (keystore.properties) to install over
# an existing build.
#
# --fdroid (foss release only) additionally regenerates the self-hosted F-Droid
# repo — fdroidserver in a venv (created on first use), listing metadata from
# ./fdroid/metadata/ — and mirrors it under $FDROID_S3_PREFIX/repo. The repo
# index is signed with the app's release key (keystore.properties), so users
# verify that same fingerprint when they add the repo URL printed at the end.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

BUILD_TYPE="release"; FLAVOR="gms"; SKIP_BUILD=0; GRADLE_ONLY=0; FDROID=0; FLAVOR_EXPLICIT=0
for a in "$@"; do
  case "$a" in
    --release) BUILD_TYPE=release ;;
    --debug) BUILD_TYPE=debug ;;
    --foss) FLAVOR=foss; FLAVOR_EXPLICIT=1 ;;
    --gms) FLAVOR=gms; FLAVOR_EXPLICIT=1 ;;
    --fdroid) FDROID=1 ;;
    --gradle-only) GRADLE_ONLY=1 ;;
    --skip-build) SKIP_BUILD=1 ;;
    --help|-h) sed -n '2,/^set -euo pipefail$/p' "$0" | head -n -1; exit 0 ;;
    *) echo "unknown arg: $a" >&2; exit 1 ;;
  esac
done
if [ "$FDROID" -eq 1 ]; then
  [ "$BUILD_TYPE" = release ] || { echo "--fdroid publishes release builds only" >&2; exit 1; }
  if [ "$FLAVOR_EXPLICIT" -eq 1 ] && [ "$FLAVOR" != foss ]; then
    echo "--fdroid publishes the foss flavor (F-Droid must not link GMS) — drop --gms" >&2; exit 1
  fi
  FLAVOR=foss
fi

S3_ENDPOINT="${S3_ENDPOINT:-https://minio-s1001.bigt.ai}"
S3_BUCKET="${S3_BUCKET:-apps}"
S3_PREFIX="${S3_PREFIX:-android-remote-control-mcp/apk}"
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
APP_ID=""
if [ -n "$AAPT" ]; then
  BADGING="$("$AAPT" dump badging "$OUT_APK")"
  APP_ID="$(printf '%s\n' "$BADGING" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
  VERSION_NAME="$(printf '%s\n' "$BADGING" | sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p")"
  VERSION_CODE="$(printf '%s\n' "$BADGING" | sed -n "s/^package:.*versionCode='\([^']*\)'.*/\1/p")"
fi
[ -n "$APP_ID" ] || [ "$FDROID" -eq 0 ] || { echo "--fdroid needs ANDROID_HOME (aapt) to read the package name from $OUT_APK" >&2; exit 1; }
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

# --fdroid: regenerate the self-hosted F-Droid repo (workspace under $FDROID_DIR,
# listing text from ./fdroid/metadata/) and mirror it to $FDROID_S3_PREFIX/repo.
# The repo index is signed with the app's release key (keystore.properties), so
# one keystore covers both the APK and the catalog.
publish_fdroid() {
  local fd_dir="${FDROID_DIR:-$OUT_DIR/fdroid}"
  local s3_prefix="${FDROID_S3_PREFIX:-android-remote-control-mcp/fdroid}"
  local venv="${FDROID_VENV:-${XDG_CACHE_HOME:-$HOME/.cache}/android-remote-control-mcp/fdroid-venv}"
  local repo_url="${FDROID_REPO_URL:-${PUBLIC_BASE_URL%/}/$s3_prefix/repo}"
  local props="$ROOT/keystore.properties"

  # fdroidserver needs JDK tools (`jar`, `keytool`) and drives apksigner
  # (located via ANDROID_HOME). A bare JRE `java` on PATH is not enough.
  if ! command -v jar >/dev/null 2>&1; then
    local jdk=""
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/jar" ]; then
      jdk="$JAVA_HOME"
    else
      for d in "$HOME"/.local/java-* /usr/lib/jvm/*; do
        if [ -x "$d/bin/jar" ]; then jdk="$d"; fi
      done
    fi
    if [ -n "$jdk" ]; then
      PATH="$jdk/bin:$PATH"
    else
      echo "fdroid: a JDK is required (jar on PATH, or JAVA_HOME / ~/.local/java-* / /usr/lib/jvm/*)" >&2; exit 1
    fi
  fi

  if [ ! -x "$venv/bin/fdroid" ]; then
    echo "fdroid: creating fdroidserver venv at $venv (one-time, needs network)"
    python3 -m venv "$venv"
    "$venv/bin/pip" install -q --disable-pip-version-check fdroidserver
  fi
  local FDBIN="$venv/bin/fdroid"

  # Sign with the release key Gradle uses for the APK (keystore.properties); the
  # FDROID_KEY* overrides exist for a separately managed repo key.
  local keystore keyalias kspass
  keystore="${FDROID_KEYSTORE:-$(sed -n 's/^storeFile=//p' "$props" 2>/dev/null | head -1)}"
  keyalias="${FDROID_KEY_ALIAS:-$(sed -n 's/^keyAlias=//p' "$props" 2>/dev/null | head -1)}"
  kspass="${FDROID_KEY_PASS:-$(sed -n 's/^storePassword=//p' "$props" 2>/dev/null | head -1)}"
  [ -f "$keystore" ] || { echo "fdroid: keystore not found: ${keystore:-<none>} — set storeFile/keyAlias/storePassword in keystore.properties, or FDROID_KEYSTORE/FDROID_KEY_ALIAS/FDROID_KEY_PASS" >&2; exit 1; }
  [ -n "$keyalias" ] && [ -n "$kspass" ] || { echo "fdroid: keystore alias/password missing — see keystore.properties or FDROID_KEY_ALIAS/FDROID_KEY_PASS" >&2; exit 1; }

  # fdroidserver records the DN in config.yml; take the certificate's own so the
  # config can never disagree with the key.
  local keydname
  keydname="$(keytool -list -v -keystore "$keystore" -storepass "$kspass" -alias "$keyalias" 2>/dev/null | sed -n 's/^Owner: //p' | head -1)"
  [ -n "$keydname" ] || { echo "fdroid: cannot read the certificate DN for alias '$keyalias' in $keystore" >&2; exit 1; }

  [ -d "$ROOT/fdroid/metadata" ] || { echo "fdroid: $ROOT/fdroid/metadata is missing" >&2; exit 1; }
  mkdir -p "$fd_dir/repo" "$fd_dir/metadata"
  cp -f "$ROOT"/fdroid/metadata/*.yml "$fd_dir/metadata/"
  cat > "$fd_dir/config.yml" <<EOF
repo_url: $repo_url
repo_name: ${FDROID_REPO_NAME:-Android Remote Control MCP}
repo_description: ${FDROID_REPO_DESCRIPTION:-FOSS builds of Android Remote Control MCP, published by bigt.ai}
keystore: $keystore
repo_keyalias: $keyalias
keystorepass: $kspass
keypass: $kspass
keydname: $keydname
EOF
  chmod 600 "$fd_dir/config.yml"
  cp -f "$OUT_APK" "$fd_dir/repo/${APP_ID}_${VERSION_CODE}.apk"

  ( cd "$fd_dir" && "$FDBIN" update )

  docker run --rm \
    -e S3_ENDPOINT="$S3_ENDPOINT" -e S3_ACCESS_KEY="$S3_ACCESS_KEY" -e S3_SECRET_KEY="$S3_SECRET_KEY" \
    -e S3_BUCKET="$S3_BUCKET" -e S3_PREFIX="$s3_prefix" \
    -v "$fd_dir/repo:/fdroid-repo:ro" --entrypoint /bin/sh "$MC_IMAGE" -c \
    'mc alias set up "$S3_ENDPOINT" "$S3_ACCESS_KEY" "$S3_SECRET_KEY" >/dev/null && \
     mc mirror --overwrite --remove /fdroid-repo "up/$S3_BUCKET/$S3_PREFIX/repo"' \
    || { echo "fdroid upload failed — check S3_* creds / MC_IMAGE ($MC_IMAGE)" >&2; exit 1; }

  local fp
  fp="$(keytool -list -v -keystore "$keystore" -storepass "$kspass" 2>/dev/null | sed -n 's/.*SHA256: //p' | head -1 || true)"
  echo "fdroid repo: $repo_url"
  echo "fdroid key : ${fp:-fingerprint unavailable} (must match the fingerprint F-Droid shows on first add)"
}

if [ "$FDROID" -eq 1 ]; then
  publish_fdroid
fi

if [ -n "$TAG_TO_PUSH" ]; then
  git push origin "$TAG_TO_PUSH" \
    || { echo "release uploaded, but pushing $TAG_TO_PUSH failed — push the tag manually" >&2; exit 1; }
  echo "pushed: $TAG_TO_PUSH"
fi

echo "uploaded: $PUBLIC_BASE_URL/$S3_KEY_PREFIX$NAME"
echo "uploaded: $PUBLIC_BASE_URL/${S3_KEY_PREFIX}$ALIAS"
echo "manifest: $PUBLIC_BASE_URL/$S3_KEY_PREFIX$MANIFEST ($VERSION_NAME / code $VERSION_CODE)"
