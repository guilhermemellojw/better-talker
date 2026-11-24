#!/usr/bin/env bash
# Helpers compartilhados de QA entre `preflight-install.sh` e `safe-instrument.sh`.
#
# Regras:
# - funções puras: só leem/informam; quem aborta é o script chamador;
# - nada aqui instala, desinstala, limpa ou escreve no aparelho;
# - caminhos de ferramentas vêm de ANDROID_HOME (default ~/Android/Sdk).
set -uo pipefail

QA_PKG_RELEASE="com.bettertalker.app"
QA_PKG_DEBUG="com.bettertalker.app.debug"
QA_PKG_TEST="com.bettertalker.app.debug.test"
QA_CERT_RELEASE="ad1b0a302256a66aa083d76b767291eb2e3c8c4b07f07ae741dd99716233bba4"
QA_CERT_DEBUG="1389b756e137d59de009375ba366e0bc41318fbca5cf1d427adc39246dfd62a8"
QA_A34_MODEL="SM-A346M"

qa_sdk_dir() { echo "${ANDROID_HOME:-$HOME/Android/Sdk}"; }

qa_find_tool() { # <aapt2|apksigner>
  local name="$1"
  ls -1 "$(qa_sdk_dir)"/build-tools/*/"$name" 2>/dev/null | tail -1
}

qa_apk_package() { # <apk>
  local aapt2="${AAPT2:-$(qa_find_tool aapt2)}"
  [ -n "$aapt2" ] || return 1
  "$aapt2" dump badging "$1" 2>/dev/null \
    | awk -F"'" '/^package:/ {print $2; exit}'
}

qa_apk_version_code() { # <apk>
  local aapt2="${AAPT2:-$(qa_find_tool aapt2)}"
  [ -n "$aapt2" ] || return 1
  "$aapt2" dump badging "$1" 2>/dev/null \
    | awk -F"'" '/^package:/ {print $4; exit}'
}

qa_apk_cert() { # <apk>
  local signer="${APKSIGNER:-$(qa_find_tool apksigner)}"
  [ -n "$signer" ] || return 1
  "$signer" verify --print-certs "$1" 2>/dev/null \
    | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1
}

qa_apk_target_package() { # <test-apk>
  local aapt2="${AAPT2:-$(qa_find_tool aapt2)}"
  [ -n "$aapt2" ] || return 1
  "$aapt2" dump xmltree "$1" --file AndroidManifest.xml 2>/dev/null \
    | sed -n 's/.*targetPackage[^=]*="\([^"]*\)".*/\1/p' | head -1
}

qa_apk_instrumentation_runner() { # <test-apk>
  local aapt2="${AAPT2:-$(qa_find_tool aapt2)}"
  [ -n "$aapt2" ] || return 1
  "$aapt2" dump xmltree "$1" --file AndroidManifest.xml 2>/dev/null \
    | grep -o 'androidx\.test\.runner\.[A-Za-z0-9_.$]*' | head -1
}

qa_devices_all() { # linhas "serial<TAB>state"
  adb devices 2>/dev/null | awk 'NR>1 && NF>=2 {print $1"\t"$2}'
}

qa_adb_state() { # <serial>
  adb devices 2>/dev/null | awk -v s="$1" '$1==s {print $2; exit}'
}

qa_readprop() { # <serial> <prop>
  adb -s "$1" shell getprop "$2" 2>/dev/null | tr -d '\r'
}

qa_installed_path() { # <serial> <pkg>
  adb -s "$1" shell pm path "$2" 2>/dev/null \
    | tr -d '\r' | sed 's/^package://' | head -1
}

qa_installed_version_code() { # <serial> <pkg>
  adb -s "$1" shell dumpsys package "$2" 2>/dev/null \
    | sed -n 's/.*versionCode=\([0-9][0-9]*\).*/\1/p' | head -1
}

qa_pull_installed_apk() { # <serial> <pkg> <dest>
  local path
  path="$(qa_installed_path "$1" "$2")"
  [ -n "$path" ] || return 1
  adb -s "$1" pull "$path" "$3" >/dev/null 2>&1 || return 1
  [ -s "$3" ]
}

qa_cert_matches() { # <apk> <expected-cert>
  local got
  got="$(qa_apk_cert "$1")"
  [ -n "$got" ] && [ "$got" = "$2" ]
}
