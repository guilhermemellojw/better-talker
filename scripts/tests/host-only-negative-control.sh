#!/usr/bin/env bash
# Controle negativo host-only: uma falha PRESA DEVE propagar para o exit do CI.
# Não acessa ADB, emulador, dispositivo, APK de produção nem dados do usuário.
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fail() { echo "NEG-CONTROL FALHOU: $*" >&2; exit 1; }

# 1) O padrão de execução usado no workflow (set -o pipefail + tee) deve
#    retornar não-zero quando um comando do pipeline falha.
set +e
(
  set -o pipefail
  { echo "linha de teste"; false; } | tee "$WORK/pipeline.log" >/dev/null
)
pipeline_rc=$?
set -e
[ "$pipeline_rc" -ne 0 ] || fail "pipeline com falha retornou 0 (pipefail inativo)"

# 2) A matriz deve abortar com não-zero quando um pré-requisito é quebrado.
#    Usa keystore inexistente; nenhum ADB real é envolvido.
set +e
DEBUG_KEYSTORE="$WORK/keystore-inexistente" \
  "$REPO/scripts/tests/safe-instrument-matrix.sh" > "$WORK/matrix.log" 2>&1
matrix_rc=$?
set -e
[ "$matrix_rc" -ne 0 ] || fail "matriz com keystore inválido retornou 0"
grep -qF 'ABORT:' "$WORK/matrix.log" || fail "matriz não sinalizou ABORT"

# 3) A matriz certificada continua íntegra (sem relaxamento de expectativas).
grep -qF 'MATRIZ OK: todos os cenários passaram' \
  "$REPO/scripts/tests/safe-instrument-matrix.sh" \
  || fail "marcador de sucesso da matriz ausente (matriz alterada)"

echo "NEG-CONTROL OK: falha propagada (pipeline_rc=$pipeline_rc, matrix_rc=$matrix_rc)"
exit 0
