#!/usr/bin/env bash
# Matriz host-only de scripts/safe-instrument.sh com ADB simulado.
#
# Não acessa aparelho físico. Requer:
#   - Android SDK (aapt2/apksigner) via ANDROID_HOME ou ~/Android/Sdk
#   - sqlite3, keytool
#   - debug keystore em ~/.android/debug.keystore (ou DEBUG_KEYSTORE)
#
# Os APKs de fixture (app, app-alvo e androidTest) são gerados no workdir com
# aapt2 + apksigner — nenhum APK de produção é necessário.
#
# Uso:
#   scripts/tests/safe-instrument-matrix.sh
#   KEEP=1 ... # mantém o diretório temporário para inspeção
set -u

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
AAPT2="${AAPT2:-$(ls -1 "$SDK"/build-tools/*/aapt2 2>/dev/null | tail -1)}"
APKSIGNER="${APKSIGNER:-$(ls -1 "$SDK"/build-tools/*/apksigner 2>/dev/null | tail -1)}"
ANDROID_JAR="$(ls -1 "$SDK"/platforms/*/android.jar 2>/dev/null | tail -1)"
DEBUG_KEYSTORE="${DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"

fail() { echo "ABORT: $*" >&2; exit 69; }
[ -n "$AAPT2" ] && [ -n "$APKSIGNER" ] && [ -n "$ANDROID_JAR" ] \
  || fail "Android SDK incompleto (aapt2/apksigner/android.jar) em $SDK"
command -v sqlite3 >/dev/null || fail "sqlite3 ausente"
command -v keytool >/dev/null || fail "keytool ausente"
[ -f "$DEBUG_KEYSTORE" ] || fail "debug keystore ausente: $DEBUG_KEYSTORE"

WORK="$(mktemp -d)"
cleanup_work() {
  if [ "${KEEP:-0}" = "1" ]; then echo "workdir: $WORK"; else rm -rf "$WORK"; fi
}
trap cleanup_work EXIT

mkdir -p "$WORK/bin" "$WORK/out" "$WORK/results" "$WORK/apk"
cp "$REPO/scripts/tests/mock-adb.sh" "$WORK/bin/adb"
chmod +x "$WORK/bin/adb"

# ---------- fixtures ----------
FIXTURE="$WORK/fixture.db"
sqlite3 "$FIXTURE" <<'SQL'
create table notes(id text); insert into notes values('n1');
create table passages(id text); insert into passages values('p1');
create table chat_messages(id text); insert into chat_messages values('c1');
create table decision_records(id text); insert into decision_records values('d1');
create table attachments(id text, fileName text, indexed integer);
insert into attachments values('a1','rr_T.jwpub',1);
SQL
: >"$WORK/wal"; : >"$WORK/shm"

APKDIR="$WORK/apk"
cat >"$APKDIR/app-base.xml" <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.bettertalker.app.debug"
    android:versionCode="80" android:versionName="6.1.0-debug">
  <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/>
</manifest>
XML
cat >"$APKDIR/app-target.xml" <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.bettertalker.app.debug"
    android:versionCode="80" android:versionName="6.1.0-debug+qa">
  <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/>
</manifest>
XML
cat >"$APKDIR/test.xml" <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.bettertalker.app.debug.test"
    android:versionCode="80" android:versionName="6.1.0-debug">
  <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/>
  <instrumentation
      android:name="androidx.test.runner.AndroidJUnitRunner"
      android:targetPackage="com.bettertalker.app.debug"/>
</manifest>
XML

link_apk() { "$AAPT2" link -o "$2" --manifest "$1" -I "$ANDROID_JAR" >/dev/null 2>&1 \
  || fail "aapt2 link falhou: $1"; }
sign_debug() { "$APKSIGNER" sign --ks "$DEBUG_KEYSTORE" --ks-pass pass:android \
  --key-pass pass:android --ks-key-alias androiddebugkey --v1-signing-enabled false \
  --v2-signing-enabled true --v3-signing-enabled false --v4-signing-enabled false \
  --out "$2" "$1" >/dev/null 2>&1 \
  || fail "apksigner falhou: $1"; }

link_apk "$APKDIR/app-base.xml" "$APKDIR/base-unsigned.apk"
link_apk "$APKDIR/app-target.xml" "$APKDIR/target-unsigned.apk"
link_apk "$APKDIR/test.xml" "$APKDIR/test-unsigned.apk"
sign_debug "$APKDIR/base-unsigned.apk" "$APKDIR/app-base.apk"
sign_debug "$APKDIR/target-unsigned.apk" "$APKDIR/app-target.apk"
sign_debug "$APKDIR/test-unsigned.apk" "$APKDIR/test-androidTest.apk"

BASE_APK="$APKDIR/app-base.apk"
TARGET_APK="$APKDIR/app-target.apk"
TEST_APK="$APKDIR/test-androidTest.apk"

keytool -genkeypair -keystore "$WORK/other.jks" -storepass otherpass \
  -keypass otherpass -alias other -keyalg RSA -keysize 2048 -validity 1000 \
  -dname "CN=Other QA" >/dev/null 2>&1 || fail "keytool falhou"
"$APKSIGNER" sign --ks "$WORK/other.jks" --ks-pass pass:otherpass \
  --key-pass pass:otherpass --ks-key-alias other --v1-signing-enabled false \
  --v2-signing-enabled true --v3-signing-enabled false --v4-signing-enabled false \
  --out "$WORK/app-othercert.apk" "$APKDIR/base-unsigned.apk" >/dev/null 2>&1 \
  || fail "re-assinatura com chave descartável falhou"

BASE=(--serial RQCX209WR1Z --class com.test.X --allow-debug-on-a34)
FAILS=0
run() { # <nome> <esperado> <apk> <test-apk> <execute 0|1> [env...]
  local name="$1" exp="$2" apk="$3" tst="$4" ex="$5"
  shift 5
  rm -f "$WORK/calls.log" "$WORK"/*.counter "$WORK/running"
  local a initial=0
  for a in "$@"; do [ "$a" = "MOCK_INITIAL_RUNNING=1" ] && initial=1; done
  [ "$initial" = "1" ] && : >"$WORK/running"
  local extra=()
  [ "$ex" = "1" ] && extra=(--execute)
  ( cd "$REPO" && env PATH="$WORK/bin:$PATH" ANDROID_HOME="$SDK" \
      MOCK_DIR="$WORK" MOCK_LOG="$WORK/calls.log" APP_APK="$BASE_APK" \
      FIXTURE_DB="$FIXTURE" "$@" \
      ./scripts/safe-instrument.sh "${BASE[@]}" --apk "$apk" --test-apk "$tst" \
      "${extra[@]}" --results "$WORK/results/$name" ) >"$WORK/out/$name.log" 2>&1
  LAST_RC=$?
  if [ "$LAST_RC" = "$exp" ]; then
    echo "PASS $name (exit=$LAST_RC)"
  else
    echo "FAIL $name (esperado=$exp obtido=$LAST_RC)"
    FAILS=$((FAILS + 1))
  fi
}

assert_log_absent() { # <texto...>
  local t
  for t in "$@"; do
    if grep -qF -- "$t" "$WORK/calls.log" 2>/dev/null; then
      echo "  FALHOU: chamada proibida no log: $t"; FAILS=$((FAILS + 1))
    fi
  done
}
assert_out_has() { grep -qF -- "$1" "$WORK/out/$CASE.log" 2>/dev/null || { echo "  FALHOU: saída sem '$1'"; FAILS=$((FAILS + 1)); }; }
assert_out_absent() { if grep -qF -- "$1" "$WORK/out/$CASE.log" 2>/dev/null; then echo "  FALHOU: saída contém '$1'"; FAILS=$((FAILS + 1)); fi; }
assert_file() { [ -f "$1" ] || { echo "  FALHOU: arquivo ausente $1"; FAILS=$((FAILS + 1)); }; }
assert_order() { # <antes> <depois>
  local a b
  a="$(grep -nF -- "$1" "$WORK/out/$CASE.log" | head -1 | cut -d: -f1)"
  b="$(grep -nF -- "$2" "$WORK/out/$CASE.log" | head -1 | cut -d: -f1)"
  if [ -z "$a" ] || [ -z "$b" ] || [ "$a" -ge "$b" ]; then
    echo "  FALHOU: ordem '$1' antes de '$2' (linhas ${a:-?}/${b:-?})"; FAILS=$((FAILS + 1))
  fi
}

# 1-2. dry-run não invasivo
CASE=dryrun
run "$CASE" 0 "$TARGET_APK" "$TEST_APK" 0 MOCK_PID=4242
assert_log_absent "am force-stop" "install -r" "ime set" "am start"
assert_out_has "DRY-RUN" "inspeção parcial"
assert_out_absent "== PASS"

CASE=dryrun-idle
run "$CASE" 0 "$TARGET_APK" "$TEST_APK" 0
assert_log_absent "am force-stop" "install -r" "ime set"

# 3-4. sucesso e ordem do PASS
CASE=ok
run "$CASE" 0 "$TARGET_APK" "$TEST_APK" 1 MOCK_INSTRUMENT=ok
assert_out_has "== PASS"
assert_log_absent "ROLLBACK"

CASE=ok-ime
run "$CASE" 0 "$TARGET_APK" "$TEST_APK" 1 MOCK_INSTRUMENT=ok MOCK_IME_CHANGES=1
assert_out_has "== PASS"
assert_order "IME restaurado" "== PASS"

# 5. falha de IME não coexiste com PASS
CASE=ime-fail
run "$CASE" 75 "$TARGET_APK" "$TEST_APK" 1 MOCK_INSTRUMENT=ok MOCK_IME_CHANGES=1 MOCK_IME_SET_FAIL=1
assert_out_absent "== PASS"
assert_out_has "FALHOU NO CLEANUP"
assert_file "$WORK/results/$CASE/IME-NAO-RESTAURADO.txt"

# 6-7. falhas de instalação
CASE=test-install-fail
run "$CASE" 71 "$TARGET_APK" "$TEST_APK" 1 MOCK_INSTRUMENT=ok MOCK_FAIL_TEST_INSTALL=1
assert_out_absent "== PASS"
[ "$(grep -c "install -r" "$WORK/calls.log")" = "1" ] || { echo "  FALHOU: app não deveria ser instalado"; FAILS=$((FAILS + 1)); }

CASE=app-install-fail
run "$CASE" 71 "$TARGET_APK" "$TEST_APK" 1 MOCK_INSTRUMENT=ok MOCK_FAIL_APP_INSTALL=1
assert_out_absent "== PASS"
assert_out_has "ROLLBACK"

# 8-11. falhas de instrumentação
for c in failures incomplete zero crash; do
  CASE="$c"
  run "$CASE" 73 "$TARGET_APK" "$TEST_APK" 1 "MOCK_INSTRUMENT=$c"
  assert_out_absent "== PASS"
done

# 12. timeout
CASE=timeout
run "$CASE" 72 "$TARGET_APK" "$TEST_APK" 1 MOCK_INSTRUMENT=slow MOCK_SLEEP=10 INSTRUMENT_TIMEOUT=1
assert_out_absent "== PASS"

# 13-15. assinatura e snapshot
CASE=sigbad
run "$CASE" 65 "$TARGET_APK" "$TEST_APK" 1 MOCK_INSTRUMENT=ok MOCK_PULL_OTHER=1
assert_out_absent "== PASS"
[ "$(grep -c "install -r" "$WORK/calls.log")" = "0" ] || { echo "  FALHOU: não deveria instalar"; FAILS=$((FAILS + 1)); }

CASE=baddb
run "$CASE" 70 "$TARGET_APK" "$TEST_APK" 1 MOCK_INSTRUMENT=ok MOCK_BAD_DB=1
assert_out_absent "== PASS"

CASE=emptydb
run "$CASE" 70 "$TARGET_APK" "$TEST_APK" 1 MOCK_INSTRUMENT=ok MOCK_EMPTY_DB=1
assert_out_absent "== PASS"

# 16-17. APKs errados
CASE=wrongapk
run "$CASE" 65 "$TEST_APK" "$TEST_APK" 1 MOCK_INSTRUMENT=ok
assert_out_absent "== PASS"

CASE=missing-test-apk
run "$CASE" 66 "$TARGET_APK" "$WORK/nao-existe.apk" 1 MOCK_INSTRUMENT=ok
assert_out_absent "== PASS"

# 18-19. prefs/publicações alteradas
CASE=prefs-change
run "$CASE" 74 "$TARGET_APK" "$TEST_APK" 1 MOCK_INSTRUMENT=ok MOCK_PREFS_CHANGE=1
assert_out_absent "== PASS"

CASE=pubs-change
run "$CASE" 74 "$TARGET_APK" "$TEST_APK" 1 MOCK_INSTRUMENT=ok MOCK_PUBS_CHANGE=1
assert_out_absent "== PASS"

# 20. rollback malsucedido
CASE=rollback-fail
run "$CASE" 73 "$TARGET_APK" "$TEST_APK" 1 MOCK_INSTRUMENT=failures MOCK_FAIL_ROLLBACK=1
assert_out_absent "== PASS"
assert_out_has "ROLLBACK FALHOU"
assert_file "$WORK/results/$CASE/ROLLBACK-FALHOU.txt"

# 23. dry-run com IME mudando: continua somente leitura
CASE=dryrun-ime
run "$CASE" 0 "$TARGET_APK" "$TEST_APK" 0 MOCK_IME_CHANGES=1
assert_log_absent "ime set" "am force-stop" "install -r" "am start"
assert_out_has "DRY-RUN"
assert_out_absent "== PASS"

# 24. preferências: bytes mudam com o mesmo tamanho
CASE=prefs-same-size
run "$CASE" 74 "$TARGET_APK" "$TEST_APK" 1 MOCK_INSTRUMENT=ok MOCK_PREFS_SAME_SIZE=1
assert_out_absent "== PASS"

# 25-26. falhas de cópia invalidam o snapshot
CASE=prefs-copy-fail
run "$CASE" 70 "$TARGET_APK" "$TEST_APK" 1 MOCK_INSTRUMENT=ok MOCK_FAIL_PREFS_COPY=1
assert_out_absent "== PASS"

CASE=db-copy-fail
run "$CASE" 70 "$TARGET_APK" "$TEST_APK" 1 MOCK_INSTRUMENT=ok MOCK_FAIL_DB_COPY=1
assert_out_absent "== PASS"

# 27-29. relançamento do app
CASE=relaunch-ok
run "$CASE" 0 "$TARGET_APK" "$TEST_APK" 1 MOCK_INSTRUMENT=ok MOCK_INITIAL_RUNNING=1
assert_out_has "== PASS"
grep -q "shell am start" "$WORK/calls.log" || { echo "  FALHOU: sem am start"; FAILS=$((FAILS + 1)); }

CASE=relaunch-fail
run "$CASE" 77 "$TARGET_APK" "$TEST_APK" 1 MOCK_INSTRUMENT=ok MOCK_INITIAL_RUNNING=1 MOCK_START_FAIL=1
assert_out_absent "== PASS"
assert_file "$WORK/results/$CASE/RELAUNCH-FALHOU.txt"

CASE=relaunch-noop
run "$CASE" 77 "$TARGET_APK" "$TEST_APK" 1 MOCK_INSTRUMENT=ok MOCK_INITIAL_RUNNING=1 MOCK_START_NOOP=1
assert_out_absent "== PASS"
assert_file "$WORK/results/$CASE/RELAUNCH-FALHOU.txt"

# 30. A34 recusa --execute --no-freeze
CASE=a34-nofreeze
rm -f "$WORK/calls.log" "$WORK"/*.counter "$WORK/running"
( cd "$REPO" && env PATH="$WORK/bin:$PATH" ANDROID_HOME="$SDK" MOCK_DIR="$WORK" \
    MOCK_LOG="$WORK/calls.log" APP_APK="$BASE_APK" FIXTURE_DB="$FIXTURE" \
    ./scripts/safe-instrument.sh "${BASE[@]}" --apk "$TARGET_APK" \
    --test-apk "$TEST_APK" --execute --no-freeze --results "$WORK/results/$CASE" ) \
  >"$WORK/out/$CASE.log" 2>&1
LAST_RC=$?
if [ "$LAST_RC" = "65" ]; then echo "PASS $CASE (exit=65)"; else
  echo "FAIL $CASE (esperado=65 obtido=$LAST_RC)"; FAILS=$((FAILS + 1))
fi
assert_log_absent "install -r"
assert_out_absent "== PASS"

# 21-22. sinais
signal_case() { # <nome> <sinal> <esperado> <job-control 0|1>
  local name="$1" sig="$2" exp="$3" jc="$4"
  rm -f "$WORK/calls.log" "$WORK"/*.counter
  (
    [ "$jc" = "1" ] && set -m
    cd "$REPO" || exit 1
    env PATH="$WORK/bin:$PATH" ANDROID_HOME="$SDK" MOCK_DIR="$WORK" \
      MOCK_LOG="$WORK/calls.log" APP_APK="$BASE_APK" FIXTURE_DB="$FIXTURE" \
      MOCK_INSTRUMENT=slow MOCK_SLEEP=20 \
      ./scripts/safe-instrument.sh "${BASE[@]}" --apk "$TARGET_APK" \
      --test-apk "$TEST_APK" --execute --results "$WORK/results/$name" \
      >"$WORK/out/$name.log" 2>&1 &
    pid=$!
    sleep 3
    kill -"$sig" "$pid" 2>/dev/null
    wait "$pid"
    echo $? >"$WORK/exit.$name"
  )
  local rc
  rc="$(cat "$WORK/exit.$name" 2>/dev/null)"
  if [ "$rc" = "$exp" ]; then echo "PASS $name (exit=$rc)"; else
    echo "FAIL $name (esperado=$exp obtido=${rc:-?})"; FAILS=$((FAILS + 1))
  fi
  grep -qF "== PASS" "$WORK/out/$name.log" && { echo "  FALHOU: PASS indevido"; FAILS=$((FAILS + 1)); }
  grep -qF "base-antes.apk" "$WORK/calls.log" || { echo "  AVISO: sem rollback em $name"; }
}
signal_case sigint INT 130 1
signal_case sigterm TERM 143 0

echo
if [ "$FAILS" -eq 0 ]; then
  echo "MATRIZ OK: todos os cenários passaram"
  exit 0
fi
echo "MATRIZ FALHOU: $FAILS verificação(ões)"
exit 1
