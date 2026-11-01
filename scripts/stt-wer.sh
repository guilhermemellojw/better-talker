#!/usr/bin/env bash
# WER humano do STT local — empurra as amostras e roda o teste instrumentado.
#
# Uso:
#   scripts/stt-wer.sh <pasta> [serial]
#
# <pasta> deve conter:
#   - <nome>.wav (16 kHz, mono, 16-bit PCM) para cada amostra;
#   - ground_truth.txt com uma linha por amostra: "<nome>|texto exato".
#
# Requisitos: app debug instalado com o modelo baixado (Configurações → Voz).
# O script instala só o APK de teste e roda via `am instrument` — não
# desinstala o app nem apaga o modelo.
set -euo pipefail

DIR="${1:?uso: scripts/stt-wer.sh <pasta> [serial]}"
SERIAL="${2:-}"
PKG="${STT_PKG:-com.bettertalker.app.debug}"
TEST_PKG="${PKG}.test"
STAGE="/sdcard/Download/stt-wer-upload"
APP_DIR="files/stt-wer"

ADB=(adb)
if [ -n "$SERIAL" ]; then
  ADB=(adb -s "$SERIAL")
fi

[ -f "$DIR/ground_truth.txt" ] || { echo "falta $DIR/ground_truth.txt"; exit 1; }
ls "$DIR"/*.wav >/dev/null 2>&1 || { echo "nenhum .wav em $DIR"; exit 1; }

echo "→ compilando APK de teste"
(
  cd "$(dirname "$0")/../android"
  ./gradlew --no-daemon --max-workers=1 \
    -Dorg.gradle.jvmargs="-Xmx2560m -XX:MaxMetaspaceSize=512m -XX:+UseG1GC" \
    -Dkotlin.compiler.execution.strategy=in-process \
    -Dkapt.use.worker.api=false \
    :app:assembleDebugAndroidTest
)

TEST_APK="$(dirname "$0")/../android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
[ -f "$TEST_APK" ] || { echo "APK de teste ausente: $TEST_APK"; exit 1; }

echo "→ instalando APK de teste"
"${ADB[@]}" install -r "$TEST_APK" >/dev/null

echo "→ enviando amostras (staging $STAGE)"
"${ADB[@]}" shell mkdir -p "$STAGE"
"${ADB[@]}" push "$DIR"/*.wav "$STAGE/" >/dev/null
"${ADB[@]}" push "$DIR/ground_truth.txt" "$STAGE/" >/dev/null

# Copia para o armazenamento interno do app (o teste lê files/stt-wer/).
"${ADB[@]}" shell "run-as $PKG mkdir -p $APP_DIR"
for f in "$DIR"/*.wav; do
  base="$(basename "$f")"
  "${ADB[@]}" shell "cat '$STAGE/$base' | run-as $PKG sh -c 'cat > $APP_DIR/$base'"
done
"${ADB[@]}" shell "cat '$STAGE/ground_truth.txt' | run-as $PKG sh -c 'cat > $APP_DIR/ground_truth.txt'"
"${ADB[@]}" shell "run-as $PKG ls -la $APP_DIR"

echo "→ rodando SttWerTest (modelo em files/stt/)"
"${ADB[@]}" logcat -c
"${ADB[@]}" shell am instrument -w \
  -e class com.bettertalker.app.SttWerTest \
  "$TEST_PKG/androidx.test.runner.AndroidJUnitRunner" | tail -20

echo "→ resultados (tag SttWer)"
"${ADB[@]}" logcat -d -s SttWer | grep -E "LOAD\||RESULT\||SUMMARY\||SKIP\||MEM\|" || true
