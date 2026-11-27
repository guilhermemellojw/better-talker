# COORD-TRUST-BOUNDARY/P4.3 — fechamento de filesystem do runner

**Estado:** Draft; correção do bloqueio de acesso aos scripts, **sem novo
dispatch**. Nenhum merge, deploy, piloto, usuário persistente, secret de
produção, transporte real, POST, LLM ou Android foi executado.

**Baseline:** `31a150330d2b9c8af420cdfac322fde73132dda8` (P4.2).
**Dispatch que falhou:** run `46081657455`, attempt 1; causa observada:
`python3: can't open file '<path>/.opencode/scripts/coord_trust_service_cli.py':
[Errno 13] Permission denied` sob `setpriv --reuid=65533`.
**Branch:** `feat/coord-trust-crossuid-cert`.
**HEAD do código:** `17cd7d37138407a7602d4848fe994e4f9de9f53d`.
**HEAD final do PR:** publicado no corpo/comentário do PR.

## 1. Causa e correção

O checkout do runner tem ancestrais não atravessáveis pelo UID efêmero. O
harness agora prepara, antes de qualquer serviço/cliente:

1. um **runtime bundle mínimo** em `$WORK_ROOT/runtime`, fora do checkout,
   do HOME e do estado protegido;
2. diretório `0555` root e arquivos `0444` root, sem escrita pelos UIDs;
3. manifesto `runtime-manifest.json` com SHA-256 de cada arquivo e HEAD de
   origem;
4. `PYTHONPATH` e paths de serviço/cliente apontando **somente** para o bundle;
5. probes sob os UIDs efetivos de serviço e consumidor, antes do G1.

O checkout não é chmodado, o HOME não é ampliado e state/trust/âncora/chaves
não são copiados nem expostos.

### Fecho mínimo copiado

`coord_trust_service.py`, `coord_trust_service_cli.py`,
`coord_trust_crossuid_client.py`, `coord_trust_crossuid_common.py`,
`coord_trust_crossuid_crash_service.py`, `coord_authority.py`,
`coord_authority_signed.py`, `coord_communication.py`, `coord_ledger.py`,
`coord_ledger_authority.py`, `coord_p14b_authority.py`.

`coord_p14b` deixou de ser importado em runtime por
`coord_trust_crossuid_common.py` (apenas `TYPE_CHECKING`); o fecho foi validado
por importação real sob o bundle, não apenas por cópia.

## 2. Verificações sob identidades efetivas (antes de G1)

| Verificação | Serviço `65533` | Consumidor `65534` |
|---|---|---|
| interpretador executa | obrigatório | obrigatório |
| scripts do bundle legíveis | obrigatório | obrigatório |
| importação do fecho | obrigatório | obrigatório |
| ancestrais do bundle atravessáveis | obrigatório | obrigatório |
| `state/` | legível | negado |
| trust store/consumer registry | legíveis | negados |
| âncora (diretório) | legível | negada |
| chaves sintéticas | negadas | negadas |

As verificações são registradas em G0; falha interrompe o harness antes de
iniciar o serviço. Ledger/âncora arquivo são novamente verificados no sandbox
G8 depois que existem.

## 3. Regressão específica

- `test_runtime_bundle_is_minimal_read_only_and_outside_source`: cria fonte em
  diretório `0700`, copia o fecho para fora da fonte, exige manifesto/SHA,
  `0555`/`0444` e importação real dos quatro módulos.
- O harness falha com código não zero se qualquer probe de UID, script, import,
  ancestral ou negação de estado não corresponder ao esperado.
- G6 estrito, `certified_sha`, `run_attempt=1`, timeout de 10 min, limpeza
  `always()` por manifest/`RUN_ID` e sanitização permanecem inalterados.

## 4. Validação local

| Suíte | Resultado |
|---|---|
| `test_coord_trust_service.py` (P3) | **40/40 OK** |
| `test_coord_trust_crossuid.py` (P4.3) | **12/12 OK** |
| `unittest discover` | **558/558 OK** |
| `test_supervisor.py` | **167/167 passed** |
| Total | **725** |

Também passaram `py_compile`, parser YAML e `git diff --check`. Os logs locais
não são versionados. Nenhum dispatch foi autorizado/executado nesta rodada.

## 5. Limites

- A autorização anterior de dispatch foi consumida; **nenhuma nova execução**
  está autorizada.
- Cross-UID real continua restrito ao runner descartável; este host não cria
  usuários nem amplia privilégios.
- G6 permanece **BLOCKED** se não houver mudança efetiva de GID; EACCES de
  socket não certifica.
- Sem merge, deploy, piloto, transporte real, instalação ou criação de usuário.

Próximo passo: B recertifica o novo HEAD e o bundle runtime; somente depois um
novo dispatch explícito poderá ser autorizado.
