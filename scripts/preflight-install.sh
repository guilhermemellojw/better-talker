#!/usr/bin/env bash
# Pre-flight de instalação/teste do Better Talker.
#
# NÃO instala nada. Só inspeciona o APK e os aparelhos e aborta combinações
# perigosas. Use antes de qualquer `adb install` ou teste instrumentado.
#
# Uso:
#   scripts/preflight-install.sh app/build/outputs/apk/release/app-release.apk
#   ADB_SERIAL=<serial> scripts/preflight-install.sh <apk> [--allow-debug-on-a34]
#
# Regras:
# - package de release = com.bettertalker.app, assinatura de release;
# - package de debug  = com.bettertalker.app.debug (coexiste com o release);
# - A34 (SM-A346M) é release-only; teste de app limpo com .debug exige
#   --allow-debug-on-a34;
# - tablet (25040RP0AL) é debug/matrix; release não deve ser instalado nele;
# - antes de instalar release, exporte backup em Configurações > Dados.
set -euo pipefail

APK="${1:-}"
if [ -z "$APK" ]; then
  echo "uso: $0 <apk> [--allow-debug-on-a34]" >&2
  exit 64
fi
shift || true

ALLOW_DEBUG_A34=0
for arg in "$@"; do
  case "$arg" in
    --allow-debug-on-a34) ALLOW_DEBUG_A34=1 ;;
    *) echo "argumento desconhecido: $arg" >&2; exit 64 ;;
  esac
done

[ -f "$APK" ] || { echo "APK não encontrado: $APK" >&2; exit 66; }

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/qa-common.sh
. "$HERE/lib/qa-common.sh"

AAPT2="$(qa_find_tool aapt2)"
APKSIGNER="$(qa_find_tool apksigner)"
export AAPT2 APKSIGNER
[ -n "$AAPT2" ] && [ -n "$APKSIGNER" ] || {
  echo "aapt2/apksigner não encontrados em $(qa_sdk_dir)/build-tools" >&2
  exit 69
}

PKG="$(qa_apk_package "$APK")"
CERT="$(qa_apk_cert "$APK")"
RELEASE_CERT="$QA_CERT_RELEASE"
DEBUG_CERT="$QA_CERT_DEBUG"

echo "APK:      $APK"
echo "package:  $PKG"
echo "cert:     $CERT"

if [ "$PKG" != "com.bettertalker.app" ] && [ "$PKG" != "com.bettertalker.app.debug" ]; then
  echo "ABORT: package inesperado; esperado com.bettertalker.app[.debug]." >&2
  exit 65
fi

if [ "$PKG" = "com.bettertalker.app" ] && [ "$CERT" != "$RELEASE_CERT" ]; then
  echo "ABORT: applicationId de release com assinatura que NÃO é a de release." >&2
  echo "       Este é o build que substituiu o release e apagou os dados do A34." >&2
  exit 65
fi

if [ "$PKG" = "com.bettertalker.app" ] && [ "$CERT" = "$RELEASE_CERT" ]; then
  echo "OK: release assinado com a keystore de release."
  echo "    Antes de instalar: exporte backup em Configurações > Dados."
fi

if [ "$PKG" = "com.bettertalker.app.debug" ]; then
  if [ "$CERT" != "$DEBUG_CERT" ]; then
    echo "AVISO: package .debug com assinatura inesperada ($CERT)." >&2
  fi
  echo "OK: build debug separado; coexiste com o release."
  echo "    NUNCA instale este APK com applicationId com.bettertalker.app."
fi

echo "--- aparelhos"
adb devices -l || true

if [ -n "${ADB_SERIAL:-}" ]; then
  MODEL="$(qa_readprop "$ADB_SERIAL" ro.product.model || true)"
  echo "target:   $ADB_SERIAL (model=$MODEL)"
  if [ "$MODEL" = "SM-A346M" ] && [ "$PKG" = "com.bettertalker.app.debug" ] && [ "$ALLOW_DEBUG_A34" -ne 1 ]; then
    echo "ABORT: A34 é release-only; para teste de app limpo use --allow-debug-on-a34." >&2
    exit 65
  fi
  if [ "$MODEL" = "25040RP0AL" ] && [ "$PKG" = "com.bettertalker.app" ]; then
    echo "ABORT: tablet é debug/matrix; não instale o release nele." >&2
    exit 65
  fi
else
  echo "AVISO: defina ADB_SERIAL para validar o modelo do aparelho." >&2
fi

echo "Pré-flight OK. Isto NÃO instala nada."
