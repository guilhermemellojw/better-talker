#!/usr/bin/env bash
# Executor seguro de testes instrumentados por classe (dry-run por padrão).
#
# Contexto (QA-OPS-P1/P2): `connected*AndroidTest` desinstala o APK do app e o
# APK de teste ao final (AGP 8.13: ANDROID_TEST_LEAVE_APKS_INSTALLED_AFTER_RUN /
# android.injected.androidTest.leaveApksInstalledAfterRun, default false).
# Este script NUNCA desinstala, nunca dá `pm clear` e nunca chama a task Gradle.
#
# Uso:
#   scripts/safe-instrument.sh --serial <serial> --class <classe> [opções]
#
# Opções:
#   --apk <path>          APK do app (default: build local publicApp/debug)
#   --test-apk <path>     APK androidTest (default: build local)
#   --results <dir>       diretório de evidências (default: qa-results/<ts>)
#   --execute             executa de verdade; sem esta flag é dry-run
#   --allow-debug-on-a34  permite .debug no A34 (política do preflight)
#   --no-freeze           não força parada do app antes do snapshot (menos seguro)
#
# Garantias:
#   - exige serial explícito e recusa qualquer outro transporte ADB;
#   - valida package, versionCode, targetPackage e assinatura dos APKs;
#   - recusa downgrade e assinatura diferente da instalada;
#   - exige snapshot consistente (db+wal+shm, integrity_check, counts) e
#     guarda cópia do APK instalado para rollback;
#   - instala o APK de teste ANTES do app; usa somente `adb install -r`;
#   - roda `am instrument` por classe e exige "OK (N tests)";
#   - em falha após instalar, tenta restaurar o APK anterior (rollback);
#   - restaura IME e relança o app, se ele estava rodando;
#   - qualquer erro aborta com código não-zero; nunca produz falso PASS.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/qa-common.sh
. "$HERE/lib/qa-common.sh"

PKG="$QA_PKG_DEBUG"
TEST_PKG="$QA_PKG_TEST"
RUNNER="androidx.test.runner.AndroidJUnitRunner"
DEBUG_CERT="$QA_CERT_DEBUG"
INSTRUMENT_TIMEOUT="${INSTRUMENT_TIMEOUT:-1200}"

SERIAL=""
KLASS=""
APK=""
TEST_APK=""
RESULTS=""
EXECUTE=0
ALLOW_DEBUG_A34=0
FREEZE=1

usage() { sed -n '3,29p' "$0" | sed 's/^# \{0,1\}//'; }

die() { echo "ABORT: $*" >&2; exit "${2:-70}"; }

# ---------- estado para cleanup/rollback ----------
IME_BEFORE=""
APP_INSTALL_ATTEMPTED=0
INSTRUMENT_STARTED=0
APP_REPLACED=0
PASSED=0
ROLLBACK_APK=""
APP_WAS_RUNNING=""
CLEANUP_DONE=0

cleanup() {
  local rc=$?
  if [ "$CLEANUP_DONE" -eq 1 ]; then return; fi
  CLEANUP_DONE=1
  trap - EXIT INT TERM
  set +e

  if [ -n "$IME_BEFORE" ] && [ -n "$SERIAL" ]; then
    local now
    now="$(adb -s "$SERIAL" shell settings get secure default_input_method 2>/dev/null | tr -d '\r')"
    if [ -n "$now" ] && [ "$now" != "$IME_BEFORE" ]; then
      if adb -s "$SERIAL" shell ime set "$IME_BEFORE" >/dev/null 2>&1; then
        echo "IME restaurado: $now -> $IME_BEFORE"
      else
        echo "ERRO: falha ao restaurar IME ($now)" >&2
        if [ "$rc" -eq 0 ]; then rc=75; fi
      fi
    fi
  fi

  if [ "$INSTRUMENT_STARTED" -eq 1 ] && [ "$PASSED" -ne 1 ] && [ -n "$SERIAL" ]; then
    adb -s "$SERIAL" shell am force-stop "$TEST_PKG" >/dev/null 2>&1 || true
  fi

  if [ "$APP_INSTALL_ATTEMPTED" -eq 1 ] && [ "$PASSED" -ne 1 ] \
     && [ -n "$ROLLBACK_APK" ] && [ -s "$ROLLBACK_APK" ] && [ -n "$SERIAL" ]; then
    if adb -s "$SERIAL" install -r --no-streaming "$ROLLBACK_APK" >/dev/null 2>&1; then
      echo "ROLLBACK: APK anterior reinstalado ($ROLLBACK_APK)" >&2
    else
      echo "ROLLBACK FALHOU: restaure manualmente $ROLLBACK_APK" >&2
    fi
  fi

  # devolve o app ao primeiro plano se ele estava rodando antes do snapshot
  if [ -n "$APP_WAS_RUNNING" ] && [ -n "$SERIAL" ]; then
    if [ -z "$(adb -s "$SERIAL" shell pidof "$PKG" 2>/dev/null | tr -d '\r')" ]; then
      adb -s "$SERIAL" shell am start -n "$PKG/com.bettertalker.app.MainActivity" >/dev/null 2>&1 || true
    fi
  fi

  exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

while [ $# -gt 0 ]; do
  case "$1" in
    --serial) SERIAL="${2:-}"; shift 2 ;;
    --class) KLASS="${2:-}"; shift 2 ;;
    --apk) APK="${2:-}"; shift 2 ;;
    --test-apk) TEST_APK="${2:-}"; shift 2 ;;
    --results) RESULTS="${2:-}"; shift 2 ;;
    --execute) EXECUTE=1; shift ;;
    --allow-debug-on-a34) ALLOW_DEBUG_A34=1; shift ;;
    --no-freeze) FREEZE=0; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "argumento desconhecido: $1" 64 ;;
  esac
done

[ -n "$SERIAL" ] || { usage >&2; die "--serial é obrigatório" 64; }
[ -n "$KLASS" ] || { usage >&2; die "--class é obrigatório" 64; }

REPO="$(git rev-parse --show-toplevel 2>/dev/null || true)"
[ -n "$REPO" ] || die "execute dentro do repositório" 69
APK="${APK:-$REPO/android/app/build/outputs/apk/publicApp/debug/app-publicApp-debug.apk}"
TEST_APK="${TEST_APK:-$REPO/android/app/build/outputs/apk/androidTest/publicApp/debug/app-publicApp-debug-androidTest.apk}"
RESULTS="${RESULTS:-$REPO/qa-results/$(date +%Y%m%d-%H%M%S)}"

AAPT2="$(qa_find_tool aapt2)"
APKSIGNER="$(qa_find_tool apksigner)"
[ -n "$AAPT2" ] && [ -n "$APKSIGNER" ] || die "aapt2/apksigner ausentes em $(qa_sdk_dir)/build-tools" 69
export AAPT2 APKSIGNER
command -v adb >/dev/null || die "adb não encontrado" 69
command -v sqlite3 >/dev/null || die "sqlite3 não encontrado (necessário para validar snapshot)" 69

# ---------- 1. transporte ADB ----------
mapfile -t DEV_SERIALS < <(qa_devices_all | cut -f1)
[ "${#DEV_SERIALS[@]}" -gt 0 ] || die "nenhum dispositivo conectado" 69
for d in "${DEV_SERIALS[@]}"; do
  [ "$d" = "$SERIAL" ] || die "transporte extra conectado ($d); desconecte antes de rodar" 70
done
STATE="$(qa_adb_state "$SERIAL")"
[ "$STATE" = "device" ] || die "serial $SERIAL em estado '${STATE:-ausente}'" 70

MODEL="$(qa_readprop "$SERIAL" ro.product.model)"
SDK_INT="$(qa_readprop "$SERIAL" ro.build.version.sdk)"
[ -n "$MODEL" ] || die "não foi possível ler o modelo" 70
if [ "$MODEL" = "$QA_A34_MODEL" ] && [ "$ALLOW_DEBUG_A34" -ne 1 ]; then
  die "A34 com pacote .debug exige --allow-debug-on-a34 (política do preflight)" 65
fi

# ---------- 2. APKs: package, versionCode, target e assinatura ----------
[ -f "$APK" ] || die "APK não encontrado: $APK" 66
[ -f "$TEST_APK" ] || die "test APK não encontrado: $TEST_APK" 66

APP_INFO_PKG="$(qa_apk_package "$APK")"
APP_VCODE="$(qa_apk_version_code "$APK")"
APP_CERT="$(qa_apk_cert "$APK")"
TST_PKG="$(qa_apk_package "$TEST_APK")"
TST_CERT="$(qa_apk_cert "$TEST_APK")"
TST_TARGET="$(qa_apk_target_package "$TEST_APK")"
TST_RUNNER="$(qa_apk_instrumentation_runner "$TEST_APK")"

[ -n "$APP_INFO_PKG" ] || die "aapt2 não leu o APK do app" 65
[ -n "$APP_VCODE" ] || die "APK do app sem versionCode" 65
[ "$APP_INFO_PKG" = "$PKG" ] || die "APK do app tem package '$APP_INFO_PKG' (esperado $PKG)" 65
if [ "$APP_INFO_PKG" = "$QA_PKG_RELEASE" ]; then die "recusado: pacote release" 65; fi
if [ "$PKG" = "$QA_PKG_RELEASE" ]; then die "recusado: alvo é o pacote release" 65; fi
[ "$APP_CERT" = "$DEBUG_CERT" ] || die "APK do app sem a debug key esperada" 65
[ "$TST_PKG" = "$TEST_PKG" ] || die "APK de teste tem package '$TST_PKG' (esperado $TEST_PKG)" 65
[ "$TST_CERT" = "$DEBUG_CERT" ] || die "APK de teste sem a debug key esperada" 65
[ "$TST_TARGET" = "$PKG" ] || die "APK de teste aponta para '$TST_TARGET' (esperado $PKG)" 65
[ -n "$TST_RUNNER" ] || die "APK de teste sem instrumentation runner androidx.test" 65
[ "$TST_RUNNER" = "$RUNNER" ] || die "runner do APK de teste é '$TST_RUNNER' (esperado $RUNNER)" 65

# ---------- 3. pacote instalado ----------
APP_PATH="$(qa_installed_path "$SERIAL" "$PKG")"
INSTALLED=0
INST_VCODE=""
if [ -n "$APP_PATH" ]; then
  INSTALLED=1
  INST_VCODE="$(qa_installed_version_code "$SERIAL" "$PKG")"
  mkdir -p "$RESULTS/rollback"
  ROLLBACK_APK="$RESULTS/rollback/base-antes.apk"
  qa_pull_installed_apk "$SERIAL" "$PKG" "$ROLLBACK_APK" \
    || die "não foi possível salvar o APK instalado para rollback" 70
  [ "$(qa_apk_cert "$ROLLBACK_APK")" = "$DEBUG_CERT" ] \
    || die "APK instalado não tem a debug key; instalar -r apagaria dados" 65
  if [ -n "$INST_VCODE" ] && [ "$APP_VCODE" -lt "$INST_VCODE" ]; then
    die "APK ($APP_VCODE) é downgrade do instalado ($INST_VCODE); -d é proibido" 65
  fi
else
  echo "AVISO: $PKG não está instalado; o snapshot não terá dados." >&2
fi

INSTALLED_SHA=""
if [ -f "$ROLLBACK_APK" ]; then
  INSTALLED_SHA="$(sha256sum "$ROLLBACK_APK" | cut -d' ' -f1)"
fi
APK_SHA="$(sha256sum "$APK" | cut -d' ' -f1)"
APP_NEEDS_INSTALL=1
if [ -n "$INSTALLED_SHA" ] && [ "$INSTALLED_SHA" = "$APK_SHA" ]; then
  APP_NEEDS_INSTALL=0
fi

# ---------- 4. snapshot consistente ----------
prefs_fingerprint() { # <dir>
  find "$1" -type f -printf '%f %s\n' 2>/dev/null | sort | sha256sum | cut -d' ' -f1
}
mkdir -p "$RESULTS/snapshot"
APP_WAS_RUNNING="$(adb -s "$SERIAL" shell pidof "$PKG" 2>/dev/null | tr -d '\r')"
if [ "$FREEZE" -eq 1 ]; then
  adb -s "$SERIAL" shell am force-stop "$PKG" >/dev/null 2>&1 || true
else
  echo "AVISO: --no-freeze; snapshot pode não ser consistente." >&2
fi

for f in better-talker.db better-talker.db-wal better-talker.db-shm; do
  adb -s "$SERIAL" exec-out run-as "$PKG" cat "databases/$f" >"$RESULTS/snapshot/$f" 2>/dev/null || true
done
[ -s "$RESULTS/snapshot/better-talker.db" ] || die "snapshot vazio (app não debuggable?)" 70

DB="$RESULTS/snapshot/better-talker.db"
INTEG="$(sqlite3 "$DB" 'pragma integrity_check;' 2>/dev/null | head -1 || true)"
[ "$INTEG" = "ok" ] || die "snapshot inválido (integrity_check='${INTEG:-ilegível}')" 70
JOURNAL="$(sqlite3 "$DB" 'pragma journal_mode;' 2>/dev/null || true)"
NOTES="$(sqlite3 "$DB" 'select count(*) from notes;' 2>/dev/null || echo ERR)"
PASSAGES="$(sqlite3 "$DB" 'select count(*) from passages;' 2>/dev/null || echo ERR)"
CHAT="$(sqlite3 "$DB" 'select count(*) from chat_messages;' 2>/dev/null || echo ERR)"
DECS="$(sqlite3 "$DB" 'select count(*) from decision_records;' 2>/dev/null || echo ERR)"
ATTS="$(sqlite3 "$DB" 'select count(*) from attachments;' 2>/dev/null || echo ERR)"
for v in "$NOTES" "$PASSAGES" "$CHAT" "$DECS" "$ATTS"; do
  case "$v" in ''|*[!0-9]*) die "snapshot ilegível (contagens não numéricas)" 70 ;; esac
done
if [ "$NOTES" -le 0 ] || [ "$PASSAGES" -le 0 ]; then
  die "snapshot sem dados esperados (notes=$NOTES passages=$PASSAGES)" 70
fi

# prefs/datastore (pequenos): copia para o snapshot
mkdir -p "$RESULTS/snapshot/prefs"
for d in shared_prefs datastore; do
  for name in $(adb -s "$SERIAL" shell run-as "$PKG" ls -1 "$d" 2>/dev/null | tr -d '\r'); do
    adb -s "$SERIAL" exec-out run-as "$PKG" cat "$d/$name" \
      >"$RESULTS/snapshot/prefs/$d-$name" 2>/dev/null || true
  done
done
PREFS_COUNT="$(find "$RESULTS/snapshot/prefs" -type f | wc -l | tr -d ' ')"
PREFS_FP="$(prefs_fingerprint "$RESULTS/snapshot/prefs")"

# inventário de publicações (metadados; não copia os arquivos grandes)
adb -s "$SERIAL" shell run-as "$PKG" ls -l files/publicacoes 2>/dev/null | tr -d '\r' \
  >"$RESULTS/snapshot/publicacoes.txt" || true
PUBS_FP="$(sha256sum "$RESULTS/snapshot/publicacoes.txt" | cut -d' ' -f1)"

# anexos indexados sem arquivo presente
MISSING_FILES="$(sqlite3 "$DB" 'select fileName from attachments where indexed=1;' | while read -r name; do
  [ -z "$name" ] && continue
  grep -q -- "$name" "$RESULTS/snapshot/publicacoes.txt" || echo "$name"
done)"
if [ -n "$MISSING_FILES" ]; then
  echo "AVISO: anexo indexado sem arquivo: $MISSING_FILES" >&2
fi

# espaço/escrita para restauração
adb -s "$SERIAL" shell "run-as $PKG sh -c 'test -w databases'" \
  || die "databases sem escrita; restauração não seria possível" 70

IME_BEFORE="$(adb -s "$SERIAL" shell settings get secure default_input_method | tr -d '\r')"
DB_SHA="$(sha256sum "$DB" | cut -d' ' -f1)"

cat >"$RESULTS/preflight.txt" <<EOF
timestamp=$(date -Is)
git_head=$(git -C "$REPO" rev-parse HEAD)
serial=$SERIAL
model=$MODEL sdk=$SDK_INT
apk=$APK
apk_sha256=$APK_SHA
apk_pkg=$APP_INFO_PKG apk_versionCode=$APP_VCODE apk_cert=$APP_CERT
test_apk=$TEST_APK
test_apk_sha256=$(sha256sum "$TEST_APK" | cut -d' ' -f1)
test_pkg=$TST_PKG test_target=$TST_TARGET runner=$TST_RUNNER
installed=$INSTALLED installed_versionCode=${INST_VCODE:-}
installed_sha256=${INSTALLED_SHA:-} needs_install=$APP_NEEDS_INSTALL
db_sha256=$DB_SHA integrity=$INTEG journal_mode=$JOURNAL
notes=$NOTES decisions=$DECS attachments=$ATTS passages=$PASSAGES chat=$CHAT
prefs_snapshot_files=$PREFS_COUNT prefs_fingerprint=$PREFS_FP
publicacoes_fingerprint=$PUBS_FP
ime=$IME_BEFORE
missing_attachment_files=${MISSING_FILES:-none}
EOF

echo "== preflight OK"
echo "   device: $MODEL (SDK $SDK_INT) serial=$SERIAL"
echo "   apk:    $APP_INFO_PKG v$APP_VCODE sha=${APK_SHA:0:16}…"
echo "   teste:  $TST_PKG -> $TST_TARGET"
echo "   banco:  integrity=$INTEG journal=$JOURNAL notes=$NOTES passages=$PASSAGES"
echo "   prefs:  $PREFS_COUNT arquivo(s) no snapshot"
echo "   IME:    $IME_BEFORE"

if [ "$EXECUTE" -ne 1 ]; then
  echo
  echo "== DRY-RUN (nenhuma instalação, nenhum teste)"
  if [ "$APP_NEEDS_INSTALL" -eq 1 ]; then
    echo "   instalaria o app: adb install -r (sha divergente)"
  else
    echo "   app: já é o mesmo binário; nada a instalar"
  fi
  echo "   instalaria o teste: adb install -r $TEST_APK"
  echo "   executaria: adb shell am instrument -w -e class $KLASS $TEST_PKG/$RUNNER"
  echo "   rollback: $ROLLBACK_APK"
  echo "   resultados: $RESULTS"
  exit 0
fi

# ---------- 5. execução ----------
# APK de teste primeiro: se falhar, o app ainda é o original.
adb -s "$SERIAL" install -r --no-streaming "$TEST_APK" >/dev/null \
  || die "falha ao instalar o APK de teste (app original intacto)" 71

if [ "$APP_NEEDS_INSTALL" -eq 1 ]; then
  APP_INSTALL_ATTEMPTED=1
  adb -s "$SERIAL" install -r --no-streaming "$APK" >/dev/null \
    || die "falha ao instalar o APK do app (rollback será tentado)" 71
  APP_REPLACED=1
fi

INSTRUMENT_STARTED=1
set +e
timeout "$INSTRUMENT_TIMEOUT" adb -s "$SERIAL" shell am instrument -w \
  -e class "$KLASS" "$TEST_PKG/$RUNNER" 2>&1 | tee "$RESULTS/instrument.txt"
RC=${PIPESTATUS[0]}
set -e

[ "$RC" -eq 124 ] && die "instrumentação excedeu ${INSTRUMENT_TIMEOUT}s" 72
[ "$RC" -ne 0 ] && die "instrumentação retornou $RC" 72
grep -qE '^OK \([1-9][0-9]* tests?\)$' "$RESULTS/instrument.txt" \
  || die "saída sem 'OK (N tests)' — teste não passou" 73
if grep -qE 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=' "$RESULTS/instrument.txt"; then
  die "saída contém falha de instrumentação" 73
fi

# ---------- 6. pós-condições ----------
[ -n "$(qa_installed_path "$SERIAL" "$PKG")" ] || die "pacote $PKG desapareceu" 74
# banco: o app não é relançado; se estiver rodando, congela antes da 2ª cópia
if [ -n "$(adb -s "$SERIAL" shell pidof "$PKG" 2>/dev/null | tr -d '\r')" ] && [ "$FREEZE" -eq 1 ]; then
  adb -s "$SERIAL" shell am force-stop "$PKG" >/dev/null 2>&1 || true
fi
for f in better-talker.db better-talker.db-wal better-talker.db-shm; do
  adb -s "$SERIAL" exec-out run-as "$PKG" cat "databases/$f" \
    >"$RESULTS/snapshot/after-$f" 2>/dev/null || true
done
N2="$(sqlite3 "$RESULTS/snapshot/after-better-talker.db" 'select count(*) from notes;' 2>/dev/null || echo ERR)"
P2="$(sqlite3 "$RESULTS/snapshot/after-better-talker.db" 'select count(*) from passages;' 2>/dev/null || echo ERR)"
if [ "$N2" != "$NOTES" ] || [ "$P2" != "$PASSAGES" ]; then
  die "dados mudaram (notes $NOTES->$N2, passages $PASSAGES->$P2)" 74
fi

# preferências/DataStore e arquivos de publicações não podem mudar
mkdir -p "$RESULTS/snapshot/prefs-after"
for d in shared_prefs datastore; do
  for name in $(adb -s "$SERIAL" shell run-as "$PKG" ls -1 "$d" 2>/dev/null | tr -d '\r'); do
    adb -s "$SERIAL" exec-out run-as "$PKG" cat "$d/$name" \
      >"$RESULTS/snapshot/prefs-after/$d-$name" 2>/dev/null || true
  done
done
if [ "$(prefs_fingerprint "$RESULTS/snapshot/prefs-after")" != "$PREFS_FP" ]; then
  die "preferências/DataStore mudaram durante o teste" 74
fi
adb -s "$SERIAL" shell run-as "$PKG" ls -l files/publicacoes 2>/dev/null | tr -d '\r' \
  >"$RESULTS/snapshot/publicacoes-after.txt" || true
if [ "$(sha256sum "$RESULTS/snapshot/publicacoes-after.txt" | cut -d' ' -f1)" != "$PUBS_FP" ]; then
  die "arquivos de publicações mudaram durante o teste" 74
fi

PASSED=1
echo
echo "== PASS: $KLASS"
echo "   resultados: $RESULTS"
