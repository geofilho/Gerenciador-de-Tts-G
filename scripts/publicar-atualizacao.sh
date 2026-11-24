#!/usr/bin/env bash
# Publica a versão compilada para a opção "Verificar atualização" do app.
# Uso: scripts/publicar-atualizacao.sh "Novidade 1" "Novidade 2" ...
# Antes, gere os APKs com scripts/build.sh. Depois, faça commit e push.
set -euo pipefail
cd "$(dirname "$0")/.."
CODE=$(grep -oP "versionCode \K[0-9]+" app/build.gradle)
NAME=$(grep -oP "versionName '\K[^']+" app/build.gradle)
APK="build/GerenciadorTtsG-$NAME.apk"
[ -f "$APK" ] || { echo "APK $APK não encontrado"; exit 1; }
[ $# -gt 0 ] || { echo "Informe as novidades"; exit 1; }
mkdir -p atualizacao
cp "$APK" atualizacao/GerenciadorTtsG.apk
# Desde a 1.59: APKs só de 64 e só de 32 bits, menores, que a atualização escolhe pelo aparelho.
SPLIT=0
if [ -f "build/GerenciadorTtsG-$NAME-64.apk" ] && [ -f "build/GerenciadorTtsG-$NAME-32.apk" ]; then
  cp "build/GerenciadorTtsG-$NAME-64.apk" atualizacao/GerenciadorTtsG-64.apk
  cp "build/GerenciadorTtsG-$NAME-32.apk" atualizacao/GerenciadorTtsG-32.apk
  SPLIT=1
fi
python3 - "$SPLIT" "$CODE" "$NAME" "$@" <<'PY'
import json, sys
split, code, name, news = sys.argv[1] == "1", int(sys.argv[2]), sys.argv[3], sys.argv[4:]
info = {
    "versionCode": code,
    "versionName": name,
    "apk": "https://raw.githubusercontent.com/geofilho/Gerenciador-de-Tts-G/main/atualizacao/GerenciadorTtsG.apk",
    "novidades": "\n".join("- " + n for n in news),
}
if split:
    base = "https://raw.githubusercontent.com/geofilho/Gerenciador-de-Tts-G/main/atualizacao/"
    info["apk_arm64"] = base + "GerenciadorTtsG-64.apk"
    info["apk_arm32"] = base + "GerenciadorTtsG-32.apk"
open("atualizacao/versao.json", "w", encoding="utf-8").write(json.dumps(info, ensure_ascii=False, indent=2) + "\n")
PY
cat atualizacao/versao.json
