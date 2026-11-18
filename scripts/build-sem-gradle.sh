#!/usr/bin/env bash
# Gera o APK sem Gradle, só com aapt, javac, dx, zipalign e apksigner.
# Uso: ANDROID_JAR=/caminho/android.jar scripts/build-sem-gradle.sh
# No Ubuntu/Debian: apt install aapt dalvik-exchange zipalign apksigner openjdk-17-jdk
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_JAR:?defina ANDROID_JAR com o android.jar da API 34}"
OUT=build/manual
rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes"
SRC=app/src/main
VERSION_CODE=$(grep -oP "versionCode \K[0-9]+" app/build.gradle)
VERSION_NAME=$(grep -oP "versionName '\K[^']+" app/build.gradle)

# O manifesto do Gradle não tem "package"; o aapt antigo precisa dele.
sed 's#<manifest xmlns:android="http://schemas.android.com/apk/res/android">#<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="br.com.geofilho.ttsg">#' \
  "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"

aapt package -f -m -J "$OUT/gen" -M "$OUT/AndroidManifest.xml" -S "$SRC/res" -I "$ANDROID_JAR" \
  --min-sdk-version 21 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -F "$OUT/app.unsigned.apk"

javac -nowarn -Xlint:-options -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
  -d "$OUT/classes" $(find "$SRC/java" "$OUT/gen" -name '*.java')

dalvik-exchange --dex --min-sdk-version=21 --output="$OUT/classes.dex" "$OUT/classes"
(cd "$OUT" && aapt add -f app.unsigned.apk classes.dex >/dev/null)

zipalign -f -p 4 "$OUT/app.unsigned.apk" "$OUT/app.aligned.apk"

KS=${KEYSTORE:-$HOME/.android/gerenciador-tts-g.keystore}
if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias ttsg \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Gerenciador de TTS G" >/dev/null 2>&1
fi
apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --ks-key-alias ttsg \
  --min-sdk-version 21 --out "build/GerenciadorTtsG-$VERSION_NAME.apk" "$OUT/app.aligned.apk"
apksigner verify "build/GerenciadorTtsG-$VERSION_NAME.apk"
ls -la "build/GerenciadorTtsG-$VERSION_NAME.apk"
