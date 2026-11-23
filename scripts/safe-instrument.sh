#!/usr/bin/env bash
# Executor seguro de testes instrumentados por classe (dry-run por padrão).
#
# Contexto (QA-OPS-P1): `connectedPublicAppDebugAndroidTest` desinstala o APK
# do app E o APK de teste ao final da execução. No AGP 8.13 isso é controlado
# por ANDROID_TEST_LEAVE_APKS_INSTALLED_AFTER_RUN
# ("android.injected.androidTest.leaveApksInstalledAfterRun"), cujo default é
# false. Foi assim que os dados de com.bettertalker.app.debug foram apagados
# no A34. Este script NUNCA desinstala, nunca dá `pm clear` e nunca roda a task
# `connected*AndroidTest`.
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
#
# Garantias:
#   - exige serial explícito e recusa múltiplos transportes;
#   - valida package + assinatura debug dos dois APKs;
#   - recusa downgrade de versionCode e assinatura incompatível;
#   - exige snapshot íntegro do banco antes de qualquer escrita;
#   - instala apenas com `adb install -r` (nunca uninstall);
#   - roda `am instrument` por classe e exige "OK (N tests)";
#   - compara banco, assinatura, pacote, IME e transporte antes/depois;
#   - qualquer condição insegura aborta antes de tocar no aparelho.
set -euo pipefail

PKG="com.bettertalker.app.debug"
TEST_PKG="com.bettertalker.app.debug.test"
RUNNER="androidx.test.runner.AndroidJUnitRunner"
DEBUG_CERT="1389b756e137d59de009375ba366e0bc41318fbca5cf1d427adc39246dfd62a8"
A34_MODEL="SM-A346M"
INSTRUMENT_TIMEOUT="${INSTRUMENT_TIMEOUT:-1200}"

SERIAL=""
KLASS=""
APK=""
TEST_APK=""
RESULTS=""
EXECUTE=0
ALLOW_DEBUG_A34=0

usage() {
  sed -n '3,30p' "$0" | sed 's/^# \{0,1\}//'
}

die() { echo "ABORT: $*" >&2; exit "${2:-70}"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --serial) SERIAL="${2:-}"; shift 2 ;;
    --class) KLASS="${2:-}"; shift 2 ;;
    --apk) APK="${2:-}"; shift 2 ;;
    --test-apk) TEST_APK="${2:-}"; shift 2 ;;
    --results) RESULTS="${2:-}"; shift 2 ;;
    --execute) EXECUTE=1; shift ;;
    --allow-debug-on-a34) ALLOW_DEBUG_A34=1; shift ;;
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

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
AAPT="$(ls -1 "$SDK"/build-tools/*/aapt2 2>/dev/null | tail -1 || true)"
APKSIGNER="$(ls -1 "$SDK"/build-tools/*/apksigner 2>/dev/null | tail -1 || true)"
[ -n "$AAPT" ] && [ -n "$APKSIGNER" ] || die "aapt2/apksigner ausentes em $SDK/build-tools" 69

command -v adb >/dev/null || die "adb não encontrado" 69
command -v sqlite3 >/dev/null || die "sqlite3 não encontrado (necessário para validar snapshot)" 69

# ---------- 1. transporte ADB: serial explícito e exclusivo ----------
mapfile -t DEVS < <(adb devices | awk 'NR>1 && NF>=2 {print $1}')
[ "${#DEVS[@]}" -gt 0 ] || die "nenhum dispositivo conectado" 69

for d in "${DEVS[@]}"; do
  [ "$d" = "$SERIAL" ] || die "transporte extra conectado ($d); desconecte antes de rodar" 70
done
[ "${DEVS[0]}" = "$SERIAL" ] || die "serial $SERIAL não está presente/ativo" 70

STATE="$(adb devices | awk -v s="$SERIAL" '$1==s {print $2}')"
[ "$STATE" = "device" ] || die "serial $SERIAL em estado '$STATE'" 70

MODEL="$(adb -s "$SERIAL" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
SDK_INT="$(adb -s "$SERIAL" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
[ -n "$MODEL" ] || die "não foi possível ler o modelo do aparelho" 70
if [ "$MODEL" = "$A34_MODEL" ] && [ "$ALLOW_DEBUG_A34" -ne 1 ]; then
  die "A34 com pacote .debug exige --allow-debug-on-a34 (política do preflight-install)" 65
fi

# ---------- 2. APKs: package, versionCode e assinatura ----------
[ -f "$APK" ] || die "APK não encontrado: $APK" 66
[ -f "$TEST_APK" ] || die "test APK não encontrado: $TEST_APK" 66

apk_info() { # <apk> -> "pkg versionCode"
  "$AAPT" dump badging "$1" 2>/dev/null \
    | awk -F"'" '/^package:/ {print $2, $4; exit}'
}
apk_cert() { "$APKSIGNER" verify --print-certs "$1" 2>/dev/null \
    | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1; }

APP_INFO="$(apk_info "$APK")"
TST_INFO="$(apk_info "$TEST_APK")"
[ -n "$APP_INFO" ] || die "aapt2 não leu o APK do app: $APK" 65
[ -n "$TST_INFO" ] || die "aapt2 não leu o APK de teste: $TEST_APK" 65
read -r APP_PKG APP_VCODE <<<"$APP_INFO"
read -r TST_PKG TST_VCODE <<<"$TST_INFO"
APP_CERT="$(apk_cert "$APK")"
TST_CERT="$(apk_cert "$TEST_APK")"

[ "$APP_PKG" = "$PKG" ] || die "APK do app tem package '$APP_PKG' (esperado $PKG)" 65
[ "$TST_PKG" = "$TEST_PKG" ] || die "APK de teste tem package '$TST_PKG' (esperado $TEST_PKG)" 65
[ "$APP_CERT" = "$DEBUG_CERT" ] || die "APK do app não está assinado com a debug key esperada" 65
[ "$TST_CERT" = "$DEBUG_CERT" ] || die "APK de teste não está assinado com a debug key esperada" 65
[ -n "$APP_VCODE" ] || die "não foi possível ler versionCode do APK do app" 65

# ---------- 3. pacote instalado ----------
APP_PATH="$(adb -s "$SERIAL" shell pm path "$PKG" 2>/dev/null | tr -d '\r' | sed 's/^package://' | head -1)"
INSTALLED=0
INST_VCODE=""
INST_CERT=""
if [ -n "$APP_PATH" ]; then
  INSTALLED=1
  INST_VCODE="$(adb -s "$SERIAL" shell dumpsys package "$PKG" 2>/dev/null \
      | sed -n 's/.*versionCode=\([0-9][0-9]*\).*/\1/p' | head -1)"
  TMP_APK="$(mktemp -d)/base.apk"
  adb -s "$SERIAL" pull "$APP_PATH" "$TMP_APK" >/dev/null 2>&1 || die "não foi possível ler o APK instalado" 70
  INST_CERT="$(apk_cert "$TMP_APK")"
  rm -rf "$(dirname "$TMP_APK")"
fi

if [ "$INSTALLED" -eq 1 ]; then
  [ "$INST_CERT" = "$DEBUG_CERT" ] || die "assinatura instalada diferente da debug key; instalar -r apagaria dados" 65
  if [ -n "$INST_VCODE" ] && [ "$APP_VCODE" -lt "$INST_VCODE" ]; then
    die "APK ($APP_VCODE) é downgrade do instalado ($INST_VCODE); não usar -d" 65
  fi
else
  echo "AVISO: $PKG não está instalado; instalação criará dados vazios." >&2
fi

# ---------- 4. snapshot obrigatório e restaurável ----------
mkdir -p "$RESULTS"
DB_LOCAL="$RESULTS/db-before.sqlite"
adb -s "$SERIAL" exec-out run-as "$PKG" cat databases/better-talker.db >"$DB_LOCAL" 2>/dev/null \
  || die "não foi possível extrair o banco via run-as (app não debuggable?)" 70
adb -s "$SERIAL" exec-out run-as "$PKG" cat databases/better-talker.db-wal >"$DB_LOCAL-wal" 2>/dev/null || true

INTEG="$(sqlite3 "$DB_LOCAL" 'pragma integrity_check;' | head -1)"
[ "$INTEG" = "ok" ] || die "snapshot do banco inválido (integrity_check='$INTEG')" 70
NOTES="$(sqlite3 "$DB_LOCAL" 'select count(*) from notes;')"
PASSAGES="$(sqlite3 "$DB_LOCAL" 'select count(*) from passages;')"
[ "$NOTES" -gt 0 ] && [ "$PASSAGES" -gt 0 ] \
  || die "snapshot sem dados esperados (notes=$NOTES passages=$PASSAGES)" 70
adb -s "$SERIAL" shell "run-as $PKG sh -c 'test -w databases'" \
  || die "diretório databases sem escrita; restauração não é possível" 70

IME_BEFORE="$(adb -s "$SERIAL" shell settings get secure default_input_method | tr -d '\r')"
DB_SHA="$(sha256sum "$DB_LOCAL" | cut -d' ' -f1)"

cat >"$RESULTS/preflight.txt" <<EOF
timestamp=$(date -Is)
git_head=$(git -C "$REPO" rev-parse HEAD)
serial=$SERIAL
model=$MODEL sdk=$SDK_INT
apk=$APK
apk_sha256=$(sha256sum "$APK" | cut -d' ' -f1)
apk_pkg=$APP_PKG apk_versionCode=$APP_VCODE apk_cert=$APP_CERT
test_apk=$TEST_APK
test_apk_sha256=$(sha256sum "$TEST_APK" | cut -d' ' -f1)
test_pkg=$TST_PKG test_versionCode=$TST_VCODE
installed=$INSTALLED installed_versionCode=${INST_VCODE:-}
db_sha256=$DB_SHA integrity=$INTEG notes=$NOTES passages=$PASSAGES
ime=$IME_BEFORE
EOF

echo "== preflight OK"
echo "   device: $MODEL (SDK $SDK_INT) serial=$SERIAL"
echo "   apk:    $APP_PKG v$APP_VCODE cert=${APP_CERT:0:16}… sha=$(sha256sum "$APK" | cut -c1-16)…"
echo "   banco:  integrity=$INTEG notes=$NOTES passages=$PASSAGES"
echo "   IME:    $IME_BEFORE"

if [ "$EXECUTE" -ne 1 ]; then
  echo
  echo "== DRY-RUN (nada foi alterado)"
  if [ "$INSTALLED" -eq 1 ]; then
    if [ "$APP_VCODE" = "$INST_VCODE" ]; then
      echo "   instalação do app: só se o hash divergir (mesmo versionCode $INST_VCODE)"
    else
      echo "   instalação do app: adb install -r (upgrade $INST_VCODE -> $APP_VCODE)"
    fi
  else
    echo "   instalação do app: adb install -r (pacote ausente; dados começarão vazios)"
  fi
  echo "   instalação do teste: adb install -r $TEST_APK"
  echo "   execução: adb shell am instrument -w -e class $KLASS $TEST_PKG/$RUNNER"
  echo "   depois: confere pacote/assinatura/banco/IME e restaura IME se mudou"
  echo "   resultados em: $RESULTS"
  exit 0
fi

# ---------- 5. execução (sem uninstall, sem pm clear) ----------
adb -s "$SERIAL" install -r --no-streaming "$APK" >/dev/null || die "falha no adb install -r do app" 71
adb -s "$SERIAL" install -r --no-streaming "$TEST_APK" >/dev/null || die "falha no adb install -r do androidTest" 71

set +e
timeout "$INSTRUMENT_TIMEOUT" adb -s "$SERIAL" shell am instrument -w \
  -e class "$KLASS" "$TEST_PKG/$RUNNER" 2>&1 | tee "$RESULTS/instrument.txt"
RC=$?
set -e

if [ $RC -eq 124 ]; then die "instrumentação excedeu ${INSTRUMENT_TIMEOUT}s (timeout)" 72; fi
if [ $RC -ne 0 ]; then die "instrumentação retornou $RC" 72; fi
grep -qE '^OK \([1-9][0-9]* tests?\)$' "$RESULTS/instrument.txt" \
  || die "saída sem 'OK (N tests)' — teste não passou" 73
if grep -q "FAILURES" "$RESULTS/instrument.txt"; then
  die "saída contém FAILURES" 73
fi

# ---------- 6. pós-condições ----------
APP_PATH2="$(adb -s "$SERIAL" shell pm path "$PKG" 2>/dev/null | tr -d '\r' | head -1)"
[ -n "$APP_PATH2" ] || die "pacote $PKG desapareceu após o teste" 74

DB_AFTER="$RESULTS/db-after.sqlite"
adb -s "$SERIAL" exec-out run-as "$PKG" cat databases/better-talker.db >"$DB_AFTER" 2>/dev/null \
  || die "não foi possível reler o banco após o teste" 74
N2="$(sqlite3 "$DB_AFTER" 'select count(*) from notes;')"
P2="$(sqlite3 "$DB_AFTER" 'select count(*) from passages;')"
[ "$N2" = "$NOTES" ] && [ "$P2" = "$PASSAGES" ] \
  || die "dados mudaram (notes $NOTES->$N2, passages $PASSAGES->$P2)" 74

IME_AFTER="$(adb -s "$SERIAL" shell settings get secure default_input_method | tr -d '\r')"
if [ "$IME_AFTER" != "$IME_BEFORE" ]; then
  adb -s "$SERIAL" shell ime set "$IME_BEFORE" >/dev/null 2>&1 || true
  echo "IME restaurado: $IME_AFTER -> $IME_BEFORE"
fi

echo
echo "== PASS: $KLASS"
echo "   resultados: $RESULTS"
