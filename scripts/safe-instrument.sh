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
#   --no-freeze           só em --execute: não para o app antes do snapshot (menos seguro)
#
# Dry-run (padrão) é SOMENTE LEITURA no aparelho: não para o app, não instala,
# não altera IME e não executa teste. O snapshot do dry-run é uma inspeção
# parcial — não é anunciado como consistente.
#
# Garantias:
#   - exige serial explícito e recusa qualquer outro transporte ADB;
#   - valida package, versionCode, targetPackage e assinatura dos APKs;
#   - recusa downgrade e assinatura diferente da instalada;
#   - exige snapshot consistente: congelamento verificado por `pidof`,
#     db+wal+shm com cópia conferida, integrity_check, cobertura de todas as
#     tabelas persistentes e fingerprint lógico por tabela (inclui passages;
#     detecta edição/exclusão mesmo com contagem igual);
#   - guarda cópia do APK instalado para rollback;
#   - instala o APK de teste ANTES do app; usa somente `adb install -r`;
#   - roda `am instrument` por classe e exige "OK (N tests)";
#   - em falha após instalar, tenta restaurar o APK anterior (rollback);
#   - restaura IME e relança o app, se ele estava rodando;
#   - congelamento exige force-stop 0 + pidof exit 1 sem PID; transporte ou
#     resposta inválida falha (nunca vira "processo ausente"), inclusive na
#     leitura inicial de APP_WAS_RUNNING;
#   - cobertura lógica conferida no snapshot inicial e no posterior; tabela
#     desconhecida criada durante o teste aborta (74);
#   - falha de congelamento → 78; dados lógicos alterados → 74; relançamento
#     não confirmado → 77; IME não confirmado → 75;
#   - qualquer erro aborta com código não-zero; nunca produz falso PASS.
set -euo pipefail
umask 077

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

usage() { sed -n '3,44p' "$0" | sed 's/^# \{0,1\}//'; }

die() { echo "ABORT: $*" >&2; exit "${2:-70}"; }

# ---------- estado para cleanup/rollback ----------
IME_BEFORE=""
APP_INSTALL_ATTEMPTED=0
INSTRUMENT_STARTED=0
APP_REPLACED=0
PASS_PENDING=0
APP_FROZEN=0
SNAPSHOT_KIND="nao-inspecionado"
ROLLBACK_APK=""
APP_WAS_RUNNING=""
CLEANUP_DONE=0

cleanup() {
  local rc=$?
  if [ "$CLEANUP_DONE" -eq 1 ]; then return; fi
  CLEANUP_DONE=1
  trap - EXIT INT TERM
  set +e

  # 1. IME volta ao original; falha/leitura inválida é erro (nunca PASS).
  #    Em dry-run NÃO escreve nem lê para restaurar.
  if [ "$EXECUTE" -eq 1 ] && [ -n "$SERIAL" ] \
     && command -v read_ime >/dev/null 2>&1; then
    local now=""
    if ! now="$(read_ime)"; then
      echo "ERRO: não foi possível ler o IME no cleanup" >&2
      if [ -n "$RESULTS" ] && [ -d "$RESULTS" ]; then
        {
          echo "leitura_falhou=1"
          echo "ime_esperado=$IME_BEFORE"
          echo "serial=$SERIAL"
          echo "restaurar: adb -s $SERIAL shell ime set $IME_BEFORE"
        } >"$RESULTS/IME-NAO-RESTAURADO.txt"
      fi
      if [ "$rc" -eq 0 ]; then rc=75; fi
    elif [ -n "$IME_BEFORE" ] && [ "$now" != "$IME_BEFORE" ]; then
      if adb -s "$SERIAL" shell ime set "$IME_BEFORE" >/dev/null 2>&1; then
        echo "IME restaurado: $now -> $IME_BEFORE"
      else
        echo "ERRO: falha ao restaurar IME ($now)" >&2
        if [ -n "$RESULTS" ] && [ -d "$RESULTS" ]; then
          {
            echo "ime_esperado=$IME_BEFORE"
            echo "ime_atual=$now"
            echo "serial=$SERIAL"
            echo "restaurar: adb -s $SERIAL shell ime set $IME_BEFORE"
          } >"$RESULTS/IME-NAO-RESTAURADO.txt"
        fi
        if [ "$rc" -eq 0 ]; then rc=75; fi
      fi
    fi
  fi

  # 2. para o processo de teste, se chegou a rodar e não há PASS pendente
  if [ "$INSTRUMENT_STARTED" -eq 1 ] && [ "$PASS_PENDING" -ne 1 ] && [ -n "$SERIAL" ]; then
    adb -s "$SERIAL" shell am force-stop "$TEST_PKG" >/dev/null 2>&1 || true
  fi

  # 3. rollback do APK quando falhou antes do PASS
  if [ "$APP_INSTALL_ATTEMPTED" -eq 1 ] && [ "$PASS_PENDING" -ne 1 ] \
     && [ -n "$ROLLBACK_APK" ] && [ -s "$ROLLBACK_APK" ] && [ -n "$SERIAL" ]; then
    if adb -s "$SERIAL" install -r --no-streaming "$ROLLBACK_APK" >/dev/null 2>&1; then
      echo "ROLLBACK: APK anterior reinstalado ($ROLLBACK_APK)" >&2
    else
      echo "ROLLBACK FALHOU: restaure manualmente $ROLLBACK_APK" >&2
      if [ -n "$RESULTS" ] && [ -d "$RESULTS" ]; then
        {
          echo "rollback_apk=$ROLLBACK_APK"
          echo "rollback_sha256=$(sha256sum "$ROLLBACK_APK" 2>/dev/null | cut -d' ' -f1)"
          echo "serial=$SERIAL"
          echo "restaurar: adb -s $SERIAL install -r --no-streaming $ROLLBACK_APK"
          echo "snapshot_banco=$RESULTS/snapshot/better-talker.db"
          echo "resultados=$RESULTS"
        } >"$RESULTS/ROLLBACK-FALHOU.txt"
      fi
      if [ "$rc" -eq 0 ]; then rc=76; fi
    fi
  fi

  # 4. devolve o app ao primeiro plano se ele foi parado pelo executor.
  #    Falha ou processo ainda ausente => erro (nunca PASS).
  if [ "$APP_FROZEN" -eq 1 ] && [ -n "$APP_WAS_RUNNING" ] && [ -n "$SERIAL" ]; then
    local started=0 pid="" i
    if adb -s "$SERIAL" shell am start -n "$PKG/com.bettertalker.app.MainActivity" >/dev/null 2>&1; then
      started=1
    fi
    for i in 1 2 3 4 5 6; do
      pid="$(adb -s "$SERIAL" shell pidof "$PKG" 2>/dev/null | tr -d '\r')"
      [ -n "$pid" ] && break
      sleep 0.5
    done
    if [ "$started" -ne 1 ] || [ -z "$pid" ]; then
      echo "ERRO: app não foi relançado (am_start_ok=$started pid=${pid:-vazio})" >&2
      if [ -n "$RESULTS" ] && [ -d "$RESULTS" ]; then
        {
          echo "am_start_ok=$started"
          echo "pid=${pid:-vazio}"
          echo "serial=$SERIAL"
          echo "comando: adb -s $SERIAL shell am start -n $PKG/com.bettertalker.app.MainActivity"
        } >"$RESULTS/RELAUNCH-FALHOU.txt"
      fi
      if [ "$rc" -eq 0 ]; then rc=77; fi
    fi
  fi

  # 5. PASS só depois de todo o cleanup acima
  if [ "$PASS_PENDING" -eq 1 ] && [ "$rc" -eq 0 ]; then
    echo
    echo "== PASS: $KLASS"
    echo "   resultados: $RESULTS"
  elif [ "$PASS_PENDING" -eq 1 ] && [ "$rc" -ne 0 ]; then
    echo
    echo "== FALHOU NO CLEANUP (código $rc); NÃO é PASS" >&2
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

AAPT2="$(qa_find_tool aapt2 || true)"
APKSIGNER="$(qa_find_tool apksigner || true)"
[ -n "$AAPT2" ] && [ -n "$APKSIGNER" ] || die "aapt2/apksigner ausentes em $(qa_sdk_dir)/build-tools" 69
export AAPT2 APKSIGNER
command -v adb >/dev/null || die "adb não encontrado" 69
command -v sqlite3 >/dev/null || die "sqlite3 não encontrado (necessário para validar snapshot)" 69

# apksigner pode falhar sob carga (saída vazia); 3 tentativas antes de concluir
# que a assinatura é incompatível.
apk_cert_retry() {
  local apk="$1" c="" i
  for i in 1 2 3; do
    c="$(qa_apk_cert "$apk" 2>/dev/null || true)"
    if [ -n "$c" ]; then printf '%s' "$c"; return 0; fi
    sleep 0.2
  done
  return 1
}

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
if [ "$MODEL" = "$QA_A34_MODEL" ] && [ "$EXECUTE" -eq 1 ] && [ "$FREEZE" -ne 1 ]; then
  die "A34 exige snapshot consistente; --no-freeze é proibido em --execute neste aparelho" 65
fi

# ---------- 2. APKs: package, versionCode, target e assinatura ----------
[ -f "$APK" ] || die "APK não encontrado: $APK" 66
[ -f "$TEST_APK" ] || die "test APK não encontrado: $TEST_APK" 66

APP_INFO_PKG="$(qa_apk_package "$APK" || true)"
APP_VCODE="$(qa_apk_version_code "$APK" || true)"
APP_CERT="$(apk_cert_retry "$APK")" \
  || die "não foi possível verificar a assinatura do APK do app (apksigner falhou)" 69
TST_PKG="$(qa_apk_package "$TEST_APK" || true)"
TST_CERT="$(apk_cert_retry "$TEST_APK")" \
  || die "não foi possível verificar a assinatura do APK de teste (apksigner falhou)" 69
TST_TARGET="$(qa_apk_target_package "$TEST_APK" || true)"
TST_RUNNER="$(qa_apk_instrumentation_runner "$TEST_APK" || true)"

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
  INST_VCODE="$(qa_installed_version_code "$SERIAL" "$PKG" || true)"
  mkdir -p "$RESULTS/rollback"
  ROLLBACK_APK="$RESULTS/rollback/base-antes.apk"
  qa_pull_installed_apk "$SERIAL" "$PKG" "$ROLLBACK_APK" \
    || die "não foi possível salvar o APK instalado para rollback" 70
  ROLLBACK_CERT="$(apk_cert_retry "$ROLLBACK_APK")" \
    || die "não foi possível verificar a assinatura do APK instalado (apksigner falhou)" 69
  [ "$ROLLBACK_CERT" = "$DEBUG_CERT" ] \
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
dev_size() { # <caminho no device> -> tamanho em bytes
  local s
  s="$(adb -s "$SERIAL" shell run-as "$PKG" sh -c "wc -c < '$1'" 2>/dev/null | tr -dc '0-9')"
  [ -n "$s" ] || return 1
  printf '%s' "$s"
}
copy_if_present() { # <dev> <local>; 0=ok, 1=ausente, 2=falha
  local dev="$1" loc="$2" want
  if ! want="$(dev_size "$dev")"; then return 1; fi
  adb -s "$SERIAL" exec-out run-as "$PKG" cat "$dev" >"$loc" 2>/dev/null || return 2
  [ "$(stat -c %s "$loc" 2>/dev/null || echo x)" = "$want" ] || return 2
  return 0
}
copy_prefs_dir() { # <dir no device> <dir local>; 0=ok, 1=ausente, 2=falha
  local d="$1" out="$2" name files rc
  mkdir -p "$out" || return 2
  if ! adb -s "$SERIAL" shell run-as "$PKG" sh -c "test -d '$d'" >/dev/null 2>&1; then
    printf '%s=ausente\n' "$d" >>"$RESULTS/snapshot/prefs-manifest.txt"
    return 1
  fi
  if ! files="$(adb -s "$SERIAL" shell run-as "$PKG" ls -1 "$d" 2>/dev/null)"; then
    return 2
  fi
  for name in $files; do
    name="$(printf '%s' "$name" | tr -d '\r')"
    [ -n "$name" ] || continue
    copy_if_present "$d/$name" "$out/$d-$name"; rc=$?
    case "$rc" in
      0) printf '%s/%s\n' "$d" "$name" >>"$RESULTS/snapshot/prefs-manifest.txt" ;;
      1) return 2 ;;
      *) return 2 ;;
    esac
  done
  return 0
}
LOGICAL_TABLES="notes passages attachments chat_messages decision_records note_images image_backups outlines folders s34_outlines s34_references s34_sections s34_subsections speech_sections sub_points tombstones publication_units"
EXCLUDED_TABLES="android_metadata room_master_table sqlite_sequence"

PID_VALUE=""
pidof_state() { # 0=rodando (PID_VALUE), 1=ausente, 2=erro de transporte/resposta
  PID_VALUE=""
  local out rc_line pid
  out="$(adb -s "$SERIAL" shell "pidof $PKG; echo __rc=\$?" 2>/dev/null)" || return 2
  rc_line="$(printf '%s\n' "$out" | sed -n 's/^__rc=//p' | tr -d '\r' | head -1)"
  pid="$(printf '%s\n' "$out" | sed '/^__rc=/d' | tr -d '\r' | head -1)"
  case "$rc_line" in
    0) [ -n "$pid" ] || return 2; PID_VALUE="$pid"; return 0 ;;
    1) [ -z "$pid" ] || return 2; return 1 ;;
    *) return 2 ;;
  esac
}
freeze_app() { # força parada e confirma ausência; 0=ok, 1=falha
  FREEZE_ERROR=""
  local rc=0 st
  adb -s "$SERIAL" shell am force-stop "$PKG" >/dev/null 2>&1 || rc=$?
  if [ "$rc" -ne 0 ]; then
    FREEZE_ERROR="am force-stop exit $rc"
    return 1
  fi
  st=0
  pidof_state || st=$?
  case "$st" in
    0) FREEZE_ERROR="processo ainda ativo (pid $PID_VALUE)"; return 1 ;;
    1) return 0 ;;
    *) FREEZE_ERROR="falha ao confirmar pidof (transporte ou resposta inválida)"; return 1 ;;
  esac
}
write_freeze_evidence() { # <fase>
  { echo "fase=$1"; echo "motivo=$FREEZE_ERROR"; echo "serial=$SERIAL"; } \
    >"$RESULTS/FREEZE-FALHOU.txt"
}
read_ime() {
  local v
  v="$(adb -s "$SERIAL" shell settings get secure default_input_method 2>/dev/null | tr -d '\r')"
  [ -n "$v" ] || return 1
  printf '%s' "$v"
}
logical_coverage_missing() { # <db> -> imprime tabelas persistentes sem cobertura
  local db="$1" t missing="" tables
  tables="$(sqlite3 "$db" "select name from sqlite_master where type='table';" 2>/dev/null)" || return 2
  for t in $tables; do
    case " $EXCLUDED_TABLES " in *" $t "*) continue ;; esac
    case " $LOGICAL_TABLES " in *" $t "*) : ;; *) missing="$missing $t" ;; esac
  done
  printf '%s' "$missing"
  [ -z "$missing" ]
}
logical_table_hash() { # <db> <tabela>
  local db="$1" t="$2"
  if ! sqlite3 "$db" "select 1 from sqlite_master where type='table' and name='$t';" 2>/dev/null | grep -q 1; then
    printf 'absent'
    return 0
  fi
  sqlite3 "$db" ".dump $t" 2>/dev/null | sha256sum | cut -d' ' -f1
}
logical_fingerprint() { # <db> <arquivo-de-saida>
  local db="$1" out="$2" t h
  : >"$out" || return 1
  for t in $LOGICAL_TABLES; do
    h="$(logical_table_hash "$db" "$t")" || return 1
    printf '%s %s\n' "$t" "$h" >>"$out"
  done
}
prefs_fingerprint() { # <dir local>: hash de nomes + conteúdo
  local dir="$1"
  (
    cd "$dir" || return 1
    find . -type f -print0 | LC_ALL=C sort -z | while IFS= read -r -d '' f; do
      printf '%s ' "${f#./}"
      sha256sum "$f" | cut -d' ' -f1
    done
  ) | sha256sum | cut -d' ' -f1
}
mkdir -p "$RESULTS/snapshot"
st=0
pidof_state || st=$?
case "$st" in
  0) APP_WAS_RUNNING="$PID_VALUE" ;;
  1) APP_WAS_RUNNING="" ;;
  *)
    FREEZE_ERROR="falha ao ler o estado inicial do app (transporte ou resposta inválida)"
    write_freeze_evidence estado-inicial
    die "não foi possível confirmar o estado inicial do app" 78
    ;;
esac
if [ "$EXECUTE" -eq 1 ]; then
  if [ "$FREEZE" -eq 1 ]; then
    if freeze_app; then
      APP_FROZEN=1
      SNAPSHOT_KIND="consistente-app-parado"
    else
      write_freeze_evidence snapshot
      die "falha ao congelar o app ($FREEZE_ERROR); abortando antes de instalar" 78
    fi
  else
    SNAPSHOT_KIND="parcial-sem-freeze"
    echo "AVISO: --no-freeze; snapshot parcial (sem parar o app)." >&2
  fi
else
  # dry-run NUNCA altera estado: sem force-stop, sem instalação, sem IME
  SNAPSHOT_KIND="inspecao-parcial-somente-leitura"
  echo "dry-run: inspeção parcial do banco (app NÃO é parado)." >&2
fi

: >"$RESULTS/snapshot/arquivos-ausentes.txt"
for f in better-talker.db better-talker.db-wal better-talker.db-shm; do
  rc=0
  copy_if_present "databases/$f" "$RESULTS/snapshot/$f" || rc=$?
  case "$rc" in
    0) : ;;
    1)
      : >"$RESULTS/snapshot/$f"
      printf 'databases/%s ausente\n' "$f" >>"$RESULTS/snapshot/arquivos-ausentes.txt"
      ;;
    *) die "falha ao copiar databases/$f (snapshot incompleto)" 70 ;;
  esac
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

# prefs/datastore (pequenos): cópia verificada; falha invalida o snapshot
mkdir -p "$RESULTS/snapshot/prefs"
: >"$RESULTS/snapshot/prefs-manifest.txt"
for d in shared_prefs datastore; do
  rc=0
  copy_prefs_dir "$d" "$RESULTS/snapshot/prefs" || rc=$?
  case "$rc" in
    0) : ;;
    1) : ;;
    *) die "falha ao copiar $d (snapshot incompleto)" 70 ;;
  esac
done
PREFS_COUNT="$(find "$RESULTS/snapshot/prefs" -type f | wc -l | tr -d ' ')"
PREFS_FP="$(prefs_fingerprint "$RESULTS/snapshot/prefs" || true)"
[ -n "$PREFS_FP" ] || die "não foi possível calcular o fingerprint das preferências" 70

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

IME_BEFORE="$(read_ime || true)"
if [ "$EXECUTE" -eq 1 ] && [ -z "$IME_BEFORE" ]; then
  die "não foi possível ler o IME antes do teste" 69
fi
DB_SHA="$(sha256sum "$DB" | cut -d' ' -f1)"
cov=0
COVERAGE_MISSING="$(logical_coverage_missing "$DB")" || cov=$?
case "$cov" in
  0) : ;;
  1)
    {
      echo "tabelas_sem_cobertura=$COVERAGE_MISSING"
      echo "db=$DB"
    } >"$RESULTS/DATA-COBERTURA-FALHOU.txt"
    die "cobertura lógica incompleta:$COVERAGE_MISSING" 70
    ;;
  *) die "não foi possível verificar a cobertura lógica do banco" 70 ;;
esac
logical_fingerprint "$DB" "$RESULTS/snapshot/logical-before.txt" \
  || die "não foi possível calcular o fingerprint lógico do banco" 70
LOGICAL_BEFORE="$(sha256sum "$RESULTS/snapshot/logical-before.txt" | cut -d' ' -f1)"

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
db_sha256=$DB_SHA integrity=$INTEG journal_mode=$JOURNAL snapshot_kind=$SNAPSHOT_KIND
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
echo "   banco:  $SNAPSHOT_KIND integrity=$INTEG journal=$JOURNAL notes=$NOTES passages=$PASSAGES"
echo "   prefs:  $PREFS_COUNT arquivo(s) no snapshot"
echo "   IME:    $IME_BEFORE"

if [ "$EXECUTE" -ne 1 ]; then
  echo
  echo "== DRY-RUN (somente leitura: sem force-stop, instalação, IME ou teste)"
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
if [ "$EXECUTE" -eq 1 ] && [ "$FREEZE" -eq 1 ]; then
  st=0
  pidof_state || st=$?
  case "$st" in
    0)
      if freeze_app; then
        APP_FROZEN=1
      else
        write_freeze_evidence pos-instrumentacao
        die "falha ao congelar o app após a instrumentação ($FREEZE_ERROR)" 78
      fi
      ;;
    1) : ;;
    *)
      FREEZE_ERROR="falha ao confirmar pidof após a instrumentação (transporte ou resposta inválida)"
      write_freeze_evidence pos-instrumentacao
      die "não foi possível confirmar o estado do app após a instrumentação" 78
      ;;
  esac
fi
for f in better-talker.db better-talker.db-wal better-talker.db-shm; do
  rc=0
  copy_if_present "databases/$f" "$RESULTS/snapshot/after-$f" || rc=$?
  case "$rc" in
    0) : ;;
    1) : >"$RESULTS/snapshot/after-$f" ;;
    *) die "falha ao reler databases/$f após o teste" 74 ;;
  esac
done
cov=0
AFTER_COVERAGE_MISSING="$(logical_coverage_missing "$RESULTS/snapshot/after-better-talker.db")" || cov=$?
case "$cov" in
  0) : ;;
  1)
    {
      echo "tabelas_sem_cobertura=$AFTER_COVERAGE_MISSING"
      echo "fase=pos-instrumentacao"
      echo "db=$RESULTS/snapshot/after-better-talker.db"
    } >"$RESULTS/DATA-COBERTURA-FALHOU.txt"
    die "cobertura lógica pós-teste incompleta:$AFTER_COVERAGE_MISSING" 74
    ;;
  *) die "não foi possível verificar a cobertura lógica pós-teste" 74 ;;
esac
N2="$(sqlite3 "$RESULTS/snapshot/after-better-talker.db" 'select count(*) from notes;' 2>/dev/null || echo ERR)"
P2="$(sqlite3 "$RESULTS/snapshot/after-better-talker.db" 'select count(*) from passages;' 2>/dev/null || echo ERR)"
if [ "$N2" != "$NOTES" ] || [ "$P2" != "$PASSAGES" ]; then
  die "dados mudaram (notes $NOTES->$N2, passages $PASSAGES->$P2)" 74
fi

# comparação lógica por tabela (independe de layout físico do SQLite/WAL)
logical_fingerprint "$RESULTS/snapshot/after-better-talker.db" \
  "$RESULTS/snapshot/logical-after.txt" \
  || die "não foi possível calcular o fingerprint lógico após o teste" 74
if ! diff -u "$RESULTS/snapshot/logical-before.txt" \
  "$RESULTS/snapshot/logical-after.txt" >"$RESULTS/snapshot/logical-diff.txt"; then
  CHANGED="$(awk 'NR==FNR{b[$1]=$2;next} {if (b[$1]!=$2) print $1}' \
    "$RESULTS/snapshot/logical-before.txt" \
    "$RESULTS/snapshot/logical-after.txt" | tr '\n' ' ')"
  {
    echo "tabelas_alteradas=${CHANGED:-desconhecida}"
    echo "diff=$RESULTS/snapshot/logical-diff.txt"
    echo "snapshot_antes=$RESULTS/snapshot/better-talker.db"
    echo "snapshot_depois=$RESULTS/snapshot/after-better-talker.db"
  } >"$RESULTS/DATA-FINGERPRINT-FALHOU.txt"
  die "dados lógicos mudaram (${CHANGED:-tabela desconhecida})" 74
fi

# preferências/DataStore e arquivos de publicações não podem mudar
mkdir -p "$RESULTS/snapshot/prefs-after"
for d in shared_prefs datastore; do
  rc=0
  copy_prefs_dir "$d" "$RESULTS/snapshot/prefs-after" || rc=$?
  case "$rc" in
    0) : ;;
    1) : ;;
    *) die "falha ao reler $d após o teste" 74 ;;
  esac
done
if [ "$(prefs_fingerprint "$RESULTS/snapshot/prefs-after" || true)" != "$PREFS_FP" ]; then
  die "preferências/DataStore mudaram durante o teste" 74
fi
adb -s "$SERIAL" shell run-as "$PKG" ls -l files/publicacoes 2>/dev/null | tr -d '\r' \
  >"$RESULTS/snapshot/publicacoes-after.txt" || true
if [ "$(sha256sum "$RESULTS/snapshot/publicacoes-after.txt" | cut -d' ' -f1)" != "$PUBS_FP" ]; then
  die "arquivos de publicações mudaram durante o teste" 74
fi

# Sucesso só é anunciado no cleanup, depois de IME/rollback/relançamento.
PASS_PENDING=1
