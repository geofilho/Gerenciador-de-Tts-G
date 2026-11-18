#!/usr/bin/env bash
# Publica a versão compilada para a opção "Verificar atualização" do app.
# Uso: scripts/publicar-atualizacao.sh "Novidade 1" "Novidade 2" ...
# Antes, gere o APK com scripts/build-sem-gradle.sh. Depois, faça commit e push.
set -euo pipefail
cd "$(dirname "$0")/.."
CODE=$(grep -oP "versionCode \K[0-9]+" app/build.gradle)
NAME=$(grep -oP "versionName '\K[^']+" app/build.gradle)
APK="build/GerenciadorTtsG-$NAME.apk"
[ -f "$APK" ] || { echo "APK $APK não encontrado"; exit 1; }
[ $# -gt 0 ] || { echo "Informe as novidades"; exit 1; }
mkdir -p atualizacao
cp "$APK" atualizacao/GerenciadorTtsG.apk
python3 - "$CODE" "$NAME" "$@" <<'PY'
import json, sys
code, name, news = int(sys.argv[1]), sys.argv[2], sys.argv[3:]
info = {
    "versionCode": code,
    "versionName": name,
    "apk": "https://raw.githubusercontent.com/geofilho/Gerenciador-de-Tts-G/main/atualizacao/GerenciadorTtsG.apk",
    "novidades": "\n".join("- " + n for n in news),
}
open("atualizacao/versao.json", "w", encoding="utf-8").write(json.dumps(info, ensure_ascii=False, indent=2) + "\n")
PY
cat atualizacao/versao.json
