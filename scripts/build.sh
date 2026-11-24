#!/usr/bin/env bash
# Gera o APK assinado com o Gradle (necessário desde a 1.59, por causa do tradutor no aparelho).
# Requer local.properties com sdk.dir (Android SDK com platforms/android-34 e build-tools/34.0.0)
# e a chave em ~/.android/gerenciador-tts-g.keystore (ou na variável KEYSTORE).
set -euo pipefail
cd "$(dirname "$0")/.."
NAME=$(grep -oP "versionName '\K[^']+" app/build.gradle)
gradle --no-daemon -q :app:assembleRelease
mkdir -p build
cp app/build/outputs/apk/release/app-release.apk "build/GerenciadorTtsG-$NAME.apk"
ls -la "build/GerenciadorTtsG-$NAME.apk"
