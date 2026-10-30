#!/usr/bin/env bash
# SamCDP Android — dependency-free manual build (aapt2 + javac + d8 + apksigner)
# Usage:  SDK_DIR=/path/to/sdk-root ./build.sh
#   sdk-root/ contains:  android-14/  (build-tools 34: aapt2, d8, apksigner, zipalign)
#                        android-34/  (platform: android.jar)
set -euo pipefail
cd "$(dirname "$0")"

SDK_DIR="${SDK_DIR:-/home/z/my-project/work/android-sdk}"
BT="$SDK_DIR/android-14"        # build-tools 34 (folder name android-14 upstream)
PLAT="$SDK_DIR/android-34"      # platform 34
OUT=build
APK_UNALIGNED="$OUT/samcdp-unaligned.apk"
APK_ALIGNED="$OUT/samcdp-aligned.apk"
APK_OUT="$OUT/SamCDP-android-v1.4.9.apk"

rm -rf "$OUT" gen classes
mkdir -p "$OUT" gen classes "$OUT/dex"

echo "[1/6] aapt2 compile+link"
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$APK_UNALIGNED" \
  -I "$PLAT/android.jar" \
  --manifest AndroidManifest.xml \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code 36 --version-name 1.4.9 \
  --java gen --auto-add-overlay "$OUT/res.zip"

echo "[2/6] ecj compile"
find src gen -name "*.java" > "$OUT/sources.txt"
ECJ_LOG="$OUT/ecj.log"
java -jar "${ECJ:-$SDK_DIR/ecj.jar}" -nowarn -encoding UTF-8 \
  -source 8 -target 8 -bootclasspath "$PLAT/android.jar:$BT/core-lambda-stubs.jar" \
  -d classes @"$OUT/sources.txt" > "$ECJ_LOG" 2>&1 || true
if grep -q "ERROR in" "$ECJ_LOG" || [ ! -d classes/cc ]; then
  echo "COMPILATION FAILED:"
  grep -A2 "ERROR in" "$ECJ_LOG" | head -30
  exit 1
fi

echo "[3/6] d8 dex"
"$BT/d8" --release --min-api 26 --lib "$PLAT/android.jar" \
  --output "$OUT/dex" $(find classes -name "*.class")

echo "[4/6] package dex + native tunnel client"
if [ -f "$APK_UNALIGNED" ]; then
  # strip aapt2-added empty dirs not needed; add classes.dex
  (cd "$OUT/dex" && zip -q -u ../"$(basename "$APK_UNALIGNED")" classes.dex)
  # bundle aitun tunnel client as native lib (same pattern as samdocker)
  if [ -d jniLibs ]; then
    mkdir -p "$OUT/pkg/lib"
    cp -r jniLibs/* "$OUT/pkg/lib/"
    (cd "$OUT/pkg" && zip -q -r "../$(basename "$APK_UNALIGNED")" lib)
  fi
fi

echo "[5/6] zipalign"
"$BT/zipalign" -f 4 "$APK_UNALIGNED" "$APK_ALIGNED"

echo "[6/6] sign"
# v1.4.9: keystore 优先取仓库内 android/samcdp-debug.keystore（提交进 git，
# CI 与本地构建签名一致、升级安装不断签）；缺失时退回 SDK_DIR 内生成。
# 注意：脚本开头已 cd "$(dirname "$0")" 进入本目录，此处直接 pwd；
# 不能再用 dirname "$0"（CI 里以 `bash android/build.sh` 调用时 $0 带目录前缀，
# 二次 cd 会失败并被 set -e 终止——CI 干跑实测踩坑）
REPO_KS="$(pwd)/samcdp-debug.keystore"
KS="$SDK_DIR/samcdp-debug.keystore"   # persistent debug keystore (stable signatures across builds)
if [ -f "$REPO_KS" ]; then
  KS="$REPO_KS"
fi
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias androiddebugkey \
    -dname "CN=Android Debug,O=SamCDP,C=US" -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --min-sdk-version 26 --out "$APK_OUT" "$APK_ALIGNED"
"$BT/apksigner" verify "$APK_OUT"

ls -la "$APK_OUT"
echo "BUILD OK → $APK_OUT"
