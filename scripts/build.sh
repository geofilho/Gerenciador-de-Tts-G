#!/usr/bin/env bash
# Gera os APKs assinados com o Gradle (necessário desde a 1.59, por causa do tradutor no aparelho):
#   build/GerenciadorTtsG-<versão>.apk        completo (64 e 32 bits), para o link geral
#   build/GerenciadorTtsG-<versão>-64.apk     só 64 bits, baixado pela atualização nesses aparelhos
#   build/GerenciadorTtsG-<versão>-32.apk     só 32 bits
# Requer local.properties com sdk.dir (Android SDK com platforms/android-34 e build-tools/34.0.0)
# e a chave em ~/.android/gerenciador-tts-g.keystore (ou na variável KEYSTORE).
set -euo pipefail
cd "$(dirname "$0")/.."
NAME=$(grep -oP "versionName '\K[^']+" app/build.gradle)
gradle --no-daemon -q :app:assembleRelease
OUT=app/build/outputs/apk/release
mkdir -p build
cp "$OUT/app-universal-release.apk" "build/GerenciadorTtsG-$NAME.apk"
cp "$OUT/app-arm64-v8a-release.apk" "build/GerenciadorTtsG-$NAME-64.apk"
cp "$OUT/app-armeabi-v7a-release.apk" "build/GerenciadorTtsG-$NAME-32.apk"
ls -la build/GerenciadorTtsG-"$NAME"*.apk
