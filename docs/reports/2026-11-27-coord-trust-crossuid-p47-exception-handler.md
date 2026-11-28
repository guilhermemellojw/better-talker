# COORD-TRUST-BOUNDARY/P4.7 — fechamento do handler genérico

**Estado:** Draft; correção implementada e validada localmente; aguardando
recertificação por B/P7.8. Nenhum dispatch, rerun, merge, deploy, piloto,
transporte real, Android ou LLM pago foi executado.

**Baseline:** `6643a4d9278a5ab019ad918a52d3939332801d22` (P4.6).
**Parecer independente:** https://github.com/guilhermemellojw/better-talker/pull/87#issuecomment-7237443114
**SHA de implementação P4.7:** `c94038c21f2470a36a035c9458aade32b3d25959`.
**SHA final certificado:** definido no comentário externo de certificação em
https://github.com/guilhermemellojw/better-talker/pull/87; não é
auto-referenciado neste documento.

## 1. Bloqueante e fail-first

O handler genérico de `TrustService.handle()` usava `_safe_reason(exc)` em um
`except Exception:` sem `as exc`. Em Python 3, o nome `exc` não existe nesse
escopo; qualquer exceção não classificada escapava como `UnboundLocalError` em
vez de retornar o `SERVICE_ERROR` sanitizado.

A regressão `ServiceTest.test_unexpected_exception_returns_sanitized_internal_error`
foi escrita **antes** da correção e executada na baseline. Ela injeta
`RuntimeError("/private/path boom")` na rota produtiva `status`, passando por
`handle()` → `_dispatch()` → `_status()`:

```text
ERROR: test_unexpected_exception_returns_sanitized_internal_error
...
File "<path>/coord_trust_service.py", line 1331, in handle
    "reason": _safe_reason(exc)}}
UnboundLocalError: cannot access local variable 'exc' where it is not
associated with a value

Ran 1 test ... FAILED (errors=1)
```

A varredura AST dos arquivos alterados em P4.6
(`coord_trust_service.py`, `coord_p14b_authority.py`,
`coord_trust_crossuid_harness.py`, `coord_trust_crossuid_client.py`,
`coord_authority_signed.py`) encontrou apenas essa referência sem binder. Após a
correção, a mesma varredura não encontra nenhuma.

## 2. Correção mínima

O handler genérico agora devolve um literal, sem interpretar a exceção
desconhecida:

```python
return {"ok": False, "request_id": request_id,
        "error": {"code": "SERVICE_ERROR",
                  "message": "falha interna do serviço",
                  "reason": "INTERNAL"}}
```

Contrato preservado:

- `ok=false`;
- `code=SERVICE_ERROR`;
- `message="falha interna do serviço"`;
- `reason=INTERNAL`;
- `request_id` ecoado;
- nenhuma mensagem crua, path, tipo de exceção ou traceback na resposta.

Os handlers específicos (`ServiceError` e
`ledger.LedgerError`/`cla.AuthorityContractError`) permanecem inalterados, com
seus códigos e `reason` classificados. O `except Exception:` da auditoria dentro
do handler continua sendo absorvido; a falha de auditoria não impede a resposta
sanitizada.

## 3. Regressões

- `test_unexpected_exception_returns_sanitized_internal_error`: exceção
  inesperada na rota produtiva e falha simultânea do registro de auditoria;
  exige o contrato exato, `request_id` preservado e ausência de
  `/private/path`, `RuntimeError`, `Traceback` ou mensagem de auditoria.
- A suíte do serviço continua cobrindo `REQUEST_REPLAY`, `REPLAY_STALE`,
  `PERMISSION_DENIED`, `BAD_REQUEST`, `OUTCOME_UNKNOWN`, `TRANSPORT_DISABLED` e
  `STORAGE_SECURITY`; a suíte cross-UID cobre `CONFLICT` e
  `CRYPTO_INFRASTRUCTURE`. Nenhum desses códigos passou pelo handler genérico.
- `test_coord_trust_crossuid.py` permanece com 17 testes; nenhum contrato do
  harness ou da certificação cross-UID foi alterado.

## 4. Validação local

| Suíte | Resultado |
|---|---|
| `unittest discover` (inclui 41 serviço + 17 P4 + 14 P9) | **578/578 OK** |
| `test_coord_trust_service.py` | **41/41 OK** |
| `test_coord_trust_crossuid.py` | **17/17 OK** |
| `test_supervisor.py` | **167/167 passed** |
| Total | **745 = 578 + 167** |

Também passaram `py_compile`, parser YAML do workflow, `git diff --check` e a
varredura AST de handlers. A CI do HEAD final e o resultado do job cross-UID
(skipped sem dispatch) são registrados no comentário externo do PR #87.

## 5. Preservação e limites

- P4.6 permanece intacto: NSS efêmero do OpenSSH, mount namespace privado,
  `journey_blocked`, G0/G2/G6/G8/G9, vinculação ao SHA, sanitização, limpeza,
  `run_attempt=1`, timeout de 10 min e `real_transport=false`.
- A correção é exclusiva do handler genérico e da regressão; não amplia escopo,
  não altera o contrato de identidade UID + `SCM_CREDENTIALS`, não cria usuário
  persistente e não eleva privilégios do serviço.
- A autorização anterior permanece consumida. Nenhum dispatch/rerun foi
  executado nesta rodada.
- A certificação cross-UID real continua **BLOCKED** até B/P7.8 recertificar
  este delta e emitir autorização explícita para um único dispatch com o novo
  `certified_sha` e `run_attempt=1`.
