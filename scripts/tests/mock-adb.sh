#!/usr/bin/env bash
# ADB simulado para os testes host-only de scripts/safe-instrument.sh.
#
# Nunca toca em aparelho real: responde às chamadas que o executor faz usando
# o APK de fixture, um banco SQLite de fixture e arquivos temporários.
#
# Variáveis obrigatórias:
#   MOCK_DIR    diretório de estado (contadores e log)
#   APP_APK     APK debug assinado (simula o app instalado)
#   FIXTURE_DB  SQLite com as tabelas mínimas (notes/passages/...)
# Variáveis de cenário (opcionais):
#   MOCK_LOG, MOCK_INSTRUMENT, MOCK_SLEEP, MOCK_FAIL_*, MOCK_PULL_*,
#   MOCK_BAD_DB, MOCK_EMPTY_DB, MOCK_PID, MOCK_PREFS_CHANGE, MOCK_PUBS_CHANGE,
#   MOCK_IME_CHANGES, MOCK_IME_SET_FAIL
set -u

MOCK_DIR="${MOCK_DIR:?MOCK_DIR obrigatório}"
APP_APK="${APP_APK:?APP_APK obrigatório}"
FIXTURE_DB="${FIXTURE_DB:?FIXTURE_DB obrigatório}"
LOG="${MOCK_LOG:-$MOCK_DIR/calls.log}"
PKG=com.bettertalker.app.debug
TEST_PKG=com.bettertalker.app.debug.test

echo "$*" >>"$LOG"

if [ "${1:-}" = "devices" ]; then
  echo "List of devices attached"
  printf 'RQCX209WR1Z\tdevice\n'
  exit 0
fi
[ "${1:-}" = "-s" ] || { echo "mock: esperava -s" >&2; exit 1; }
shift 2
CMD="$*"

case "$CMD" in
  "shell getprop ro.product.model") echo SM-A346M ;;
  "shell getprop ro.build.version.sdk") echo 37 ;;
  "shell pm path $PKG"|"shell pm path "*) echo "package:/fake/base.apk" ;;
  "pull /fake/base.apk "*)
    dest="${CMD#pull /fake/base.apk }"
    if [ "${MOCK_PULL_FAIL:-0}" = "1" ]; then exit 1; fi
    if [ "${MOCK_PULL_OTHER:-0}" = "1" ]; then
      cp "$MOCK_DIR/app-othercert.apk" "$dest"
    else
      cp "$APP_APK" "$dest"
    fi
    ;;
  "shell dumpsys package $PKG"*) echo "    versionCode=80 minSdk=26 targetSdk=36" ;;
  "shell pidof $PKG"*) printf '%s' "${MOCK_PID:-}" ;;
  "shell am force-stop "*|"shell am start "*) exit 0 ;;
  "shell settings get secure default_input_method")
    if [ "${MOCK_IME_CHANGES:-0}" = "1" ]; then
      n=$(cat "$MOCK_DIR/ime.counter" 2>/dev/null || echo 0)
      echo $((n + 1)) >"$MOCK_DIR/ime.counter"
      if [ "$n" = "0" ]; then echo "com.mock.ime/.Ime"; else echo "com.mock.other/.Ime"; fi
    else
      echo "com.mock.ime/.Ime"
    fi
    ;;
  "shell ime set "*) [ "${MOCK_IME_SET_FAIL:-0}" = "1" ] && exit 1; exit 0 ;;
  shell\ run-as\ $PKG\ sh\ -c\ \'test\ -w\ databases\'*) exit 0 ;;
  shell\ run-as\ $PKG\ ls\ -1\ shared_prefs)
    if [ "${MOCK_PREFS_CHANGE:-0}" = "1" ]; then
      n=$(cat "$MOCK_DIR/prefs.counter" 2>/dev/null || echo 0)
      echo $((n + 1)) >"$MOCK_DIR/prefs.counter"
      if [ "$n" -gt 0 ]; then echo "settings.xml"; echo "extra.xml"; else echo "settings.xml"; fi
    else
      echo "settings.xml"
    fi
    ;;
  shell\ run-as\ $PKG\ ls\ -1\ datastore) exit 1 ;;
  shell\ run-as\ $PKG\ ls\ -l\ files/publicacoes)
    echo "-rw-rw-rw- 1 u0_a1 u0_a1 39639160 2026-11-23 08:49 att-855757d2-rr_T.jwpub"
    if [ "${MOCK_PUBS_CHANGE:-0}" = "1" ]; then
      n=$(cat "$MOCK_DIR/pubs.counter" 2>/dev/null || echo 0)
      echo $((n + 1)) >"$MOCK_DIR/pubs.counter"
      [ "$n" -gt 0 ] && echo "-rw-rw-rw- 1 u0_a1 u0_a1 10 2026-11-23 09:00 novo.txt"
    fi
    ;;
  exec-out\ run-as\ $PKG\ cat\ databases/better-talker.db-wal)
    if [ "${MOCK_EMPTY_DB:-0}" = "1" ]; then :; else cat "$MOCK_DIR/wal" 2>/dev/null || true; fi
    ;;
  exec-out\ run-as\ $PKG\ cat\ databases/better-talker.db-shm) cat "$MOCK_DIR/shm" 2>/dev/null || true ;;
  exec-out\ run-as\ $PKG\ cat\ databases/better-talker.db)
    if [ "${MOCK_BAD_DB:-0}" = "1" ]; then printf 'not-a-sqlite-db'
    elif [ "${MOCK_EMPTY_DB:-0}" = "1" ]; then :
    else cat "$FIXTURE_DB"; fi
    ;;
  exec-out\ run-as\ $PKG\ cat\ shared_prefs/*) echo "<map><string name=\"onboarding\">done</string></map>" ;;
  install\ -r\ --no-streaming\ *)
    apk="${CMD##* }"
    case "$apk" in
      *androidTest*) [ "${MOCK_FAIL_TEST_INSTALL:-0}" = "1" ] && exit 1 ;;
      *base-antes.apk) [ "${MOCK_FAIL_ROLLBACK:-0}" = "1" ] && exit 1 ;;
      *) [ "${MOCK_FAIL_APP_INSTALL:-0}" = "1" ] && exit 1 ;;
    esac
    exit 0
    ;;
  shell\ am\ instrument*)
    case "${MOCK_INSTRUMENT:-ok}" in
      ok) printf '\ncom.test.X:..\n\nTime: 0,2\n\nOK (2 tests)\n\n' ;;
      failures) printf '\ncom.test.X:..\n\nFAILURES!!!\nTests run: 2,  Failures: 1\n\n' ;;
      incomplete) printf '\ncom.test.X:..\n\nTime: 0,2\n\n' ;;
      crash) printf '\nINSTRUMENTATION_FAILED: com.bettertalker.app.debug.test\n\n' ;;
      zero) printf '\nOK (0 tests)\n\n' ;;
      slow) sleep "${MOCK_SLEEP:-10}"; printf '\nOK (2 tests)\n\n' ;;
    esac
    ;;
  *) echo "MOCK-UNHANDLED: $CMD" >&2; exit 1 ;;
esac
