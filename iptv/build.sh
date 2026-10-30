#!/usr/bin/env bash
# Builds MaTVLive.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner (same tools as the launcher).
# Needs a JDK 17+ and the Android SDK (build-tools 35, platform android-34). On Windows, run it from Git Bash.
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
[ -d "$SDK/build-tools" ] || { echo "Android SDK not found: set ANDROID_HOME."; exit 1; }
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
AJ="$(ls -d "$SDK"/platforms/android-*/android.jar | sort -V | tail -1)"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
CPSEP=:; BAT=; case "$(uname -s 2>/dev/null)" in MINGW*|MSYS*|CYGWIN*) CPSEP=";"; BAT=.bat ;; esac
d8() { if [ -n "$BAT" ]; then "$JAVA" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 "$@"; else "$BT/d8" "$@"; fi; }
apksigner() { if [ -n "$BAT" ]; then "$JAVA" -jar "$BT/lib/apksigner.jar" "$@"; else "$BT/apksigner" "$@"; fi; }

rm -rf out && mkdir -p out/classes out/res
"$BT/aapt2" compile --dir app/res -o out/res/res.zip
"$BT/aapt2" link -I "$AJ" --manifest app/AndroidManifest.xml -o out/base.apk out/res/res.zip --auto-add-overlay
"$JAVAC" -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$AJ$CPSEP$BT/core-lambda-stubs.jar" \
  -classpath "$AJ" -encoding UTF-8 -d out/classes $(find app/src -name '*.java')
d8 --release --min-api 26 --lib "$AJ" --output out $(find out/classes -name '*.class')
if command -v zip >/dev/null; then (cd out && zip -qj base.apk classes.dex)
else "${JAVA_HOME:+$JAVA_HOME/bin/}jar" -u -M -f out/base.apk -C out classes.dex; fi
"$BT/zipalign" -f -p 4 out/base.apk out/aligned.apk
if [ ! -f matvlive.keystore ]; then
  "$KEYTOOL" -genkeypair -keystore matvlive.keystore -alias matv -keyalg RSA -keysize 2048 -validity 20000 \
    -storepass matvlive -keypass matvlive -dname "CN=MaTV Live" >/dev/null 2>&1
fi
apksigner sign --ks matvlive.keystore --ks-pass pass:matvlive --key-pass pass:matvlive --out MaTVLive.apk out/aligned.apk
echo "Built MaTVLive.apk ($(du -h MaTVLive.apk | cut -f1))"
