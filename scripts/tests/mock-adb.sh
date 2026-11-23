#!/usr/bin/env bash
# ADB simulado para os testes host-only de scripts/safe-instrument.sh.
#
# Nunca toca em aparelho real: responde às chamadas do executor com APKs de
# fixture, banco SQLite de fixture e arquivos temporários.
#
# Obrigatórias: MOCK_DIR, APP_APK, FIXTURE_DB.
# Cenário: MOCK_LOG, MOCK_INSTRUMENT, MOCK_SLEEP, MOCK_FAIL_*, MOCK_PULL_*,
#          MOCK_BAD_DB, MOCK_EMPTY_DB, MOCK_PREFS_CHANGE, MOCK_PREFS_SAME_SIZE,
#          MOCK_PUBS_CHANGE, MOCK_IME_CHANGES, MOCK_IME_SET_FAIL,
#          MOCK_START_FAIL, MOCK_START_NOOP.
set -u

MOCK_DIR="${MOCK_DIR:?MOCK_DIR obrigatório}"
APP_APK="${APP_APK:?APP_APK obrigatório}"
FIXTURE_DB="${FIXTURE_DB:?FIXTURE_DB obrigatório}"
LOG="${MOCK_LOG:-$MOCK_DIR/calls.log}"
PKG=com.bettertalker.app.debug
TEST_PKG=com.bettertalker.app.debug.test

[ -f "$MOCK_DIR/settings-a.xml" ] || printf '<map><string name="x">AAAA</string></map>' >"$MOCK_DIR/settings-a.xml"
[ -f "$MOCK_DIR/settings-b.xml" ] || printf '<map><string name="x">BBBB</string></map>' >"$MOCK_DIR/settings-b.xml"

echo "$*" >>"$LOG"

if [ "${1:-}" = "devices" ]; then
  echo "List of devices attached"
  printf 'RQCX209WR1Z\tdevice\n'
  exit 0
fi
[ "${1:-}" = "-s" ] || { echo "mock: esperava -s" >&2; exit 1; }
shift 2
CMD="$*"
has() { case "$CMD" in *"$1"*) return 0 ;; esac; return 1; }
size_of() { stat -c %s "$1" 2>/dev/null || echo 0; }

# ---- consultas genéricas (test -d, wc -c) ----
if has "test -w databases"; then exit 0; fi
if has "test -d" && has "shared_prefs"; then exit 0; fi
if has "test -d" && has "datastore"; then exit 1; fi
if has "wc -c <"; then
  if has "databases/better-talker.db-wal"; then size_of "$MOCK_DIR/wal"; exit 0; fi
  if has "databases/better-talker.db-shm"; then size_of "$MOCK_DIR/shm"; exit 0; fi
  if has "databases/better-talker.db"; then
    n=$(cat "$MOCK_DIR/db.counter" 2>/dev/null || echo 0)
    if [ "$n" -gt 0 ] && [ -n "${MOCK_AFTER_DB:-}" ] && [ -f "${MOCK_AFTER_DB:-}" ]; then
      size_of "$MOCK_AFTER_DB"
    else
      size_of "$FIXTURE_DB"
    fi
    exit 0
  fi
  if has "shared_prefs/settings.xml"; then size_of "$MOCK_DIR/settings-a.xml"; exit 0; fi
  echo 0; exit 0
fi

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
  "shell pidof $PKG; echo __rc="*)
    if [ "${MOCK_PIDOF_TRANSPORT_FAIL:-0}" = "1" ]; then exit 1; fi
    if [ "${MOCK_PIDOF_INVALID:-0}" = "1" ]; then printf '__rc=2\n'; exit 0; fi
    if [ -f "$MOCK_DIR/running" ]; then printf '4242\n__rc=0\n'; else printf '__rc=1\n'; fi
    exit 0
    ;;
  "shell pidof $PKG"*)
    if [ -f "$MOCK_DIR/running" ]; then echo 4242; exit 0; fi
    exit 1
    ;;
  "shell am force-stop "*)
    if [ "${MOCK_FORCE_STOP_FAIL:-0}" = "1" ]; then exit 1; fi
    if [ "${MOCK_FORCE_STOP_NOOP:-0}" = "1" ]; then exit 0; fi
    case "$CMD" in *"$PKG.test"*) : ;; *) rm -f "$MOCK_DIR/running" ;; esac
    exit 0
    ;;
  "shell am start "*)
    if [ "${MOCK_START_FAIL:-0}" = "1" ]; then exit 1; fi
    if [ "${MOCK_START_NOOP:-0}" = "1" ]; then exit 0; fi
    : >"$MOCK_DIR/running"
    exit 0
    ;;
  "shell settings get secure default_input_method")
    ir=$(cat "$MOCK_DIR/imeRead.counter" 2>/dev/null || echo 0)
    echo $((ir + 1)) >"$MOCK_DIR/imeRead.counter"
    if [ "${MOCK_IME_READ_FAIL_AFTER:-0}" = "1" ] && [ "$ir" -gt 0 ]; then exit 1; fi
    if [ "${MOCK_IME_CHANGES:-0}" = "1" ]; then
      n=$(cat "$MOCK_DIR/ime.counter" 2>/dev/null || echo 0)
      echo $((n + 1)) >"$MOCK_DIR/ime.counter"
      if [ "$n" = "0" ]; then echo "com.mock.ime/.Ime"; else echo "com.mock.other/.Ime"; fi
    else
      echo "com.mock.ime/.Ime"
    fi
    ;;
  "shell ime set "*) [ "${MOCK_IME_SET_FAIL:-0}" = "1" ] && exit 1; exit 0 ;;
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
    n=$(cat "$MOCK_DIR/db.counter" 2>/dev/null || echo 0)
    echo $((n + 1)) >"$MOCK_DIR/db.counter"
    if [ "${MOCK_BAD_DB:-0}" = "1" ]; then printf 'not-a-sqlite-db'
    elif [ "${MOCK_EMPTY_DB:-0}" = "1" ]; then :
    elif [ "${MOCK_FAIL_DB_COPY:-0}" = "1" ]; then exit 1
    elif [ "$n" -gt 0 ] && [ -n "${MOCK_AFTER_DB:-}" ] && [ -f "${MOCK_AFTER_DB:-}" ]; then cat "$MOCK_AFTER_DB"
    else cat "$FIXTURE_DB"; fi
    ;;
  exec-out\ run-as\ $PKG\ cat\ shared_prefs/*)
    if [ "${MOCK_FAIL_PREFS_COPY:-0}" = "1" ]; then exit 1; fi
    if [ "${MOCK_PREFS_SAME_SIZE:-0}" = "1" ]; then
      n=$(cat "$MOCK_DIR/prefsbytes.counter" 2>/dev/null || echo 0)
      echo $((n + 1)) >"$MOCK_DIR/prefsbytes.counter"
      if [ "$n" -gt 0 ]; then cat "$MOCK_DIR/settings-b.xml"; else cat "$MOCK_DIR/settings-a.xml"; fi
    else
      cat "$MOCK_DIR/settings-a.xml"
    fi
    ;;
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
