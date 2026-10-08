#!/usr/bin/env bash
# Builds the CN standalone release APK on a bare Linux runner (Gitee Go) and attaches it to
# the Gitee release named by $1 (tag). Downloads the official DiPlay APK for the runtime
# identity, verifies it, builds with the committed CN keystore, then uploads and byte-verifies.
set -euo pipefail

TAG="${1:?usage: build_cn_release.sh <tag>}"
REPO="oneeyear/DiPlay-CN"
API="https://gitee.com/api/v5/repos/$REPO"
GITEE_TOKEN="${GITEE_TOKEN:?GITEE_TOKEN must be set}"
OFFICIAL_TAG="${OFFICIAL_TAG:-v0.2.14}"
OFFICIAL_URL="https://github.com/shihabal3amri/DiPlay/releases/download/$OFFICIAL_TAG/DiPlay-$OFFICIAL_TAG.apk"
OFFICIAL_SHA256="${OFFICIAL_SHA256:-62b31f79db32bc7c85013ae830460b697a5952fad571ed0030b97341dde0b2e3}"
WORK="$PWD"

log() { echo "[build-cn] $*"; }

# --- toolchain (no-op when the runner image already provides it) -------------------------
if ! command -v java >/dev/null || ! java -version 2>&1 | grep -qE 'version "(1[7-9]|2[0-9])'; then
  log "installing JDK 21"
  sudo apt-get update -y && sudo apt-get install -y openjdk-21-jdk-headless
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
fi
java -version

export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
if [ ! -d "$ANDROID_HOME/platforms/android-37.0" ] || [ ! -d "$ANDROID_HOME/build-tools/36.0.0" ]; then
  log "installing Android SDK (dl.google.com is China-CDN reachable)"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  if [ ! -d "$ANDROID_HOME/cmdline-tools/latest" ]; then
    curl -fsSL -o /tmp/tools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
    unzip -q /tmp/tools.zip -d "$ANDROID_HOME/cmdline-tools"
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  fi
  SDKM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
  yes | "$SDKM" --licenses >/dev/null || true
  "$SDKM" "platform-tools" "platforms;android-37.0" "build-tools;36.0.0" "ndk;28.2.13676358" >/dev/null
fi
echo "sdk.dir=$ANDROID_HOME" > "$WORK/local.properties"

# --- official identity --------------------------------------------------------------------
log "fetching official $OFFICIAL_TAG APK"
curl -fsSL --retry 5 --retry-delay 3 -o /tmp/official.apk "$OFFICIAL_URL"
echo "$OFFICIAL_SHA256  /tmp/official.apk" | sha256sum -c -
python3 "$WORK/scripts/extract_official_identity.py" /tmp/official.apk /tmp/auth-assets "$OFFICIAL_SHA256"
export DIPLAY_AUTH_ASSETS_DIR=/tmp/auth-assets

# --- build ---------------------------------------------------------------------------------
log "building $TAG"
cd "$WORK"
./gradlew --no-daemon :mobile:assembleStandaloneRelease

APK="DiPlay-cn-$TAG.apk"
cp mobile/build/outputs/apk/release/mobile-release.apk "$APK"
sha256sum "$APK" > "$APK.sha256"
python3 "$WORK/scripts/verify_apk_identity.py" mobile/build/outputs/apk/release/mobile-release.apk

# --- attach --------------------------------------------------------------------------------
id="$(curl -fsS "$API/releases/tags/$TAG?access_token=$GITEE_TOKEN" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("id") or "")' || true)"
if [ -z "$id" ]; then
  log "creating Gitee release for $TAG"
  curl -fsS -X POST "$API/releases" \
    --data-urlencode "access_token=$GITEE_TOKEN" \
    --data-urlencode "tag_name=$TAG" \
    --data-urlencode "name=DiPlay CN ${TAG#v}" \
    --data-urlencode "target_commitish=main" \
    --data-urlencode "body=See https://github.com/serein-morii/DiPlay-CN/releases/tag/$TAG" > /tmp/rel.json
  id="$(python3 -c 'import json; print(json.load(open("/tmp/rel.json")).get("id") or "")')"
fi
log "attaching to release $id"
expected="$(cut -d' ' -f1 "$APK.sha256")"
for f in "$APK.sha256" "$APK"; do
  for attempt in 1 2 3 4 5; do
    code="$(curl -sS --max-time 600 -o /tmp/attach.json -w '%{http_code}' -X POST \
      "$API/releases/$id/attach_files" -F "access_token=$GITEE_TOKEN" -F "file=@$f")"
    [ "$code" = "201" ] && break
    log "attach $f -> $code (attempt $attempt)"
    sleep 10
  done
  [ "$code" = "201" ] || { log "attaching $f failed"; exit 1; }
done

curl -fsSL --max-time 600 -o /tmp/verify.apk "$API/releases/download/$TAG/$APK" 2>/dev/null || \
  curl -fsSL --max-time 600 -o /tmp/verify.apk "https://gitee.com/$REPO/releases/download/$TAG/$APK"
got="$(sha256sum /tmp/verify.apk | cut -d' ' -f1)"
[ "$got" = "$expected" ] || { log "Gitee asset differs from the built artifact"; exit 1; }
log "done: $TAG verified on Gitee ($expected)"
