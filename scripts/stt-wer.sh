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
# O modelo deve estar baixado no app (Configurações → Voz → Baixar).
set -euo pipefail

DIR="${1:?uso: scripts/stt-wer.sh <pasta> [serial]}"
SERIAL="${2:-}"
PKG="${STT_PKG:-com.bettertalker.app.debug}"
REMOTE="/sdcard/Android/data/$PKG/files/stt-wer"

ADB=(adb)
if [ -n "$SERIAL" ]; then
  ADB=(adb -s "$SERIAL")
fi

[ -f "$DIR/ground_truth.txt" ] || { echo "falta $DIR/ground_truth.txt"; exit 1; }
ls "$DIR"/*.wav >/dev/null 2>&1 || { echo "nenhum .wav em $DIR"; exit 1; }

echo "→ enviando amostras para $REMOTE"
"${ADB[@]}" shell mkdir -p "$REMOTE"
"${ADB[@]}" push "$DIR"/*.wav "$REMOTE/" >/dev/null
"${ADB[@]}" push "$DIR/ground_truth.txt" "$REMOTE/" >/dev/null

echo "→ rodando SttWerTest (modelo em files/stt/)"
"${ADB[@]}" logcat -c
cd "$(dirname "$0")/../android"
./gradlew --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs="-Xmx2560m -XX:MaxMetaspaceSize=512m -XX:+UseG1GC" \
  -Dkotlin.compiler.execution.strategy=in-process \
  -Dkapt.use.worker.api=false \
  :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.bettertalker.app.SttWerTest

echo "→ resultados (tag SttWer)"
"${ADB[@]}" logcat -d -s SttWer | grep -E "LOAD\||RESULT\||SUMMARY\||SKIP\||MEM\|" || true
