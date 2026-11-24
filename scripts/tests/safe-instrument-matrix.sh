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
create table notes(id text, title text, updatedAt integer);
insert into notes values('n1','Nota QA',1);
create table passages(id text, attachmentId text, text text);
insert into passages values('p1','a1','texto 1');
insert into passages values('p2','a1','texto 2');
create table chat_messages(id text, payload text);
insert into chat_messages values('c1','{"text":"oi"}');
create table decision_records(id text, decisionState text, text text);
insert into decision_records values('d1','ACTIVE','decisao');
create table attachments(id text, fileName text, noteId text, indexed integer);
insert into attachments values('a1','rr_T.jwpub','n1',1);
SQL

# variantes para os testes de preservação lógica
AFTER="$WORK/after"; mkdir -p "$AFTER"
mk_after() { cp "$FIXTURE" "$AFTER/$1"; }
mk_after after-decision-delete.db
sqlite3 "$AFTER/after-decision-delete.db" "delete from decision_records;"
mk_after after-decision-edit.db
sqlite3 "$AFTER/after-decision-edit.db" "update decision_records set text='alterada' where id='d1';"
mk_after after-chat-edit.db
sqlite3 "$AFTER/after-chat-edit.db" "update chat_messages set payload='{\"text\":\"alterada\"}' where id='c1';"
mk_after after-attachment-edit.db
sqlite3 "$AFTER/after-attachment-edit.db" "update attachments set noteId='n2' where id='a1';"
mk_after after-passages-edit.db
sqlite3 "$AFTER/after-passages-edit.db" "update passages set text='alterado' where id='p1';"
mk_after after-passages-attachment.db
sqlite3 "$AFTER/after-passages-attachment.db" "update passages set attachmentId='a2' where id='p1';"
sqlite3 "$FIXTURE" "VACUUM INTO '$AFTER/after-vacuum.db';"
mk_after after-extra-table.db
sqlite3 "$AFTER/after-extra-table.db" "create table tabela_futura(id text); insert into tabela_futura values('x');"
cp "$FIXTURE" "$WORK/fixture-extra.db"
sqlite3 "$WORK/fixture-extra.db" "create table tabela_futura(id text); insert into tabela_futura values('x');"
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

echo "== mock: quoting antigo (multi-arg) deve falhar"
rm -f "$WORK/calls.log"
if env MOCK_DIR="$WORK" MOCK_LOG="$WORK/calls.log" APP_APK="$BASE_APK" FIXTURE_DB="$FIXTURE" \
   "$WORK/bin/adb" -s RQCX209WR1Z shell run-as com.bettertalker.app.debug \
   sh -c "wc -c < 'databases/better-talker.db'" >/dev/null 2>&1; then
  echo "FAIL mock-old-quoting"
  FAILS=$((FAILS + 1))
else
  echo "PASS mock-old-quoting"
fi
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
  if grep -qE "uninstall|pm clear|connected.*AndroidTest" "$WORK/calls.log" 2>/dev/null; then
    echo "  FALHOU: comando destrutivo no log"; FAILS=$((FAILS + 1))
  fi
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
if grep -q "wc -c <" "$WORK/calls.log"; then
  echo "  FALHOU: quoting antigo (wc -c <) em uso"; FAILS=$((FAILS + 1))
fi
[ -f "$WORK/results/$CASE/snapshot/prefs/shared_prefs-settings.xml" ] \
  || { echo "  FALHOU: prefs não copiadas"; FAILS=$((FAILS + 1)); }
grep -q "shared_prefs/settings.xml" "$WORK/results/$CASE/snapshot/prefs-manifest.txt" 2>/dev/null \
  || { echo "  FALHOU: manifest de prefs sem settings.xml"; FAILS=$((FAILS + 1)); }

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

# 31-34. congelamento verificável
CASE=freeze-fail-exit
run "$CASE" 78 "$TARGET_APK" "$TEST_APK" 1 MOCK_INSTRUMENT=ok MOCK_FORCE_STOP_FAIL=1
assert_out_absent "== PASS"
assert_file "$WORK/results/$CASE/FREEZE-FALHOU.txt"
[ "$(grep -c 'install -r' "$WORK/calls.log")" = "0" ] || { echo "  FALHOU: não deveria instalar"; FAILS=$((FAILS + 1)); }

CASE=freeze-fail-alive
run "$CASE" 78 "$TARGET_APK" "$TEST_APK" 1 MOCK_INSTRUMENT=ok MOCK_INITIAL_RUNNING=1 MOCK_FORCE_STOP_NOOP=1
assert_out_absent "== PASS"
assert_file "$WORK/results/$CASE/FREEZE-FALHOU.txt"
[ "$(grep -c 'install -r' "$WORK/calls.log")" = "0" ] || { echo "  FALHOU: não deveria instalar"; FAILS=$((FAILS + 1)); }

CASE=freeze-already-stopped
run "$CASE" 0 "$TARGET_APK" "$TEST_APK" 1 MOCK_INSTRUMENT=ok
assert_out_has "== PASS"

CASE=freeze-ok
run "$CASE" 0 "$TARGET_APK" "$TEST_APK" 1 MOCK_INSTRUMENT=ok MOCK_INITIAL_RUNNING=1
assert_out_has "== PASS"
grep -q "shell am start" "$WORK/calls.log" || { echo "  FALHOU: sem am start após freeze"; FAILS=$((FAILS + 1)); }

# 35-39. preservação lógica dos dados
data_case() { # <nome> <arquivo-after> <tabela-esperada>
  local n="$1" db="$2" tbl="$3"
  CASE="$n"
  run "$CASE" 74 "$TARGET_APK" "$TEST_APK" 1 MOCK_INSTRUMENT=ok "MOCK_AFTER_DB=$db"
  assert_out_absent "== PASS"
  assert_file "$WORK/results/$CASE/DATA-FINGERPRINT-FALHOU.txt"
  if ! grep -q "$tbl" "$WORK/results/$CASE/DATA-FINGERPRINT-FALHOU.txt" 2>/dev/null; then
    echo "  FALHOU: tabela $tbl não identificada"; FAILS=$((FAILS + 1))
  fi
}
data_case data-decision-delete "$AFTER/after-decision-delete.db" decision_records
data_case data-decision-edit "$AFTER/after-decision-edit.db" decision_records
data_case data-chat-edit "$AFTER/after-chat-edit.db" chat_messages
data_case data-attachment-edit "$AFTER/after-attachment-edit.db" attachments

CASE=data-vacuum
run "$CASE" 0 "$TARGET_APK" "$TEST_APK" 1 MOCK_INSTRUMENT=ok "MOCK_AFTER_DB=$AFTER/after-vacuum.db"
assert_out_has "== PASS"

# 41-42. passages: texto e vínculo (mesma contagem)
data_case data-passages-edit "$AFTER/after-passages-edit.db" passages
data_case data-passages-attachment "$AFTER/after-passages-attachment.db" passages

# 43. tabela persistente sem cobertura lógica
CASE=coverage-extra-table
run "$CASE" 70 "$TARGET_APK" "$TEST_APK" 1 MOCK_INSTRUMENT=ok "FIXTURE_DB=$WORK/fixture-extra.db"
assert_out_absent "== PASS"
assert_file "$WORK/results/$CASE/DATA-COBERTURA-FALHOU.txt"
grep -q "tabela_futura" "$WORK/results/$CASE/DATA-COBERTURA-FALHOU.txt" \
  || { echo "  FALHOU: tabela futura não identificada"; FAILS=$((FAILS + 1)); }

# 44. tabela desconhecida criada só no banco posterior
CASE=coverage-extra-after
run "$CASE" 74 "$TARGET_APK" "$TEST_APK" 1 MOCK_INSTRUMENT=ok "MOCK_AFTER_DB=$AFTER/after-extra-table.db"
assert_out_absent "== PASS"
assert_file "$WORK/results/$CASE/DATA-COBERTURA-FALHOU.txt"
grep -q "tabela_futura" "$WORK/results/$CASE/DATA-COBERTURA-FALHOU.txt" \
  || { echo "  FALHOU: tabela posterior não identificada"; FAILS=$((FAILS + 1)); }
grep -q "fase=pos-instrumentacao" "$WORK/results/$CASE/DATA-COBERTURA-FALHOU.txt" \
  || { echo "  FALHOU: fase pós-instrumentação não registrada"; FAILS=$((FAILS + 1)); }

# 45. leitura inicial do processo com falha de transporte
CASE=init-pidof-transport
run "$CASE" 78 "$TARGET_APK" "$TEST_APK" 1 MOCK_INSTRUMENT=ok MOCK_INITIAL_RUNNING=1 MOCK_PIDOF_TRANSPORT_FAIL=1
assert_out_absent "== PASS"
assert_file "$WORK/results/$CASE/FREEZE-FALHOU.txt"
grep -q "estado-inicial" "$WORK/results/$CASE/FREEZE-FALHOU.txt" \
  || { echo "  FALHOU: fase inicial não registrada"; FAILS=$((FAILS + 1)); }
[ "$(grep -c 'install -r' "$WORK/calls.log")" = "0" ] || { echo "  FALHOU: não deveria instalar"; FAILS=$((FAILS + 1)); }

# 46-47. pidof: transporte e resposta inválida
CASE=freeze-pidof-transport
run "$CASE" 78 "$TARGET_APK" "$TEST_APK" 1 MOCK_INSTRUMENT=ok MOCK_PIDOF_TRANSPORT_FAIL=1
assert_out_absent "== PASS"
assert_file "$WORK/results/$CASE/FREEZE-FALHOU.txt"
[ "$(grep -c 'install -r' "$WORK/calls.log")" = "0" ] || { echo "  FALHOU: não deveria instalar"; FAILS=$((FAILS + 1)); }

CASE=freeze-pidof-invalid
run "$CASE" 78 "$TARGET_APK" "$TEST_APK" 1 MOCK_INSTRUMENT=ok MOCK_PIDOF_INVALID=1
assert_out_absent "== PASS"
assert_file "$WORK/results/$CASE/FREEZE-FALHOU.txt"

# 40. leitura do IME falha no cleanup
CASE=ime-read-fail
run "$CASE" 75 "$TARGET_APK" "$TEST_APK" 1 MOCK_INSTRUMENT=ok MOCK_IME_READ_FAIL_AFTER=1
assert_out_absent "== PASS"
assert_file "$WORK/results/$CASE/IME-NAO-RESTAURADO.txt"

# 48-50. semântica run-as (sentinela de arquivo/diretório)
CASE=wal-absent
run "$CASE" 0 "$TARGET_APK" "$TEST_APK" 1 MOCK_INSTRUMENT=ok MOCK_WAL_SHM_ABSENT=1
assert_out_has "== PASS"
grep -q "databases/better-talker.db-wal" "$WORK/results/$CASE/snapshot/arquivos-ausentes.txt" 2>/dev/null \
  || { echo "  FALHOU: WAL ausente não registrado"; FAILS=$((FAILS + 1)); }
grep -q "databases/better-talker.db-shm" "$WORK/results/$CASE/snapshot/arquivos-ausentes.txt" 2>/dev/null \
  || { echo "  FALHOU: SHM ausente não registrado"; FAILS=$((FAILS + 1)); }

CASE=dev-size-transport-fail
run "$CASE" 70 "$TARGET_APK" "$TEST_APK" 1 MOCK_INSTRUMENT=ok MOCK_DEV_SIZE_TRANSPORT_FAIL=1
assert_out_absent "== PASS"
[ "$(grep -c 'install -r' "$WORK/calls.log")" = "0" ] || { echo "  FALHOU: não deveria instalar"; FAILS=$((FAILS + 1)); }

CASE=dev-size-permission-fail
run "$CASE" 70 "$TARGET_APK" "$TEST_APK" 1 MOCK_INSTRUMENT=ok MOCK_DEV_SIZE_PERMISSION_FAIL=1
assert_out_absent "== PASS"
[ "$(grep -c 'install -r' "$WORK/calls.log")" = "0" ] || { echo "  FALHOU: não deveria instalar"; FAILS=$((FAILS + 1)); }

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
