# COORD-05B — Política determinística de seleção de modelos (proposta)

**Estado:** proposta em PR Draft, sem execução de inferência, sem troca de sessão e sem alteração de configuração.
**Base:** `origin/main` `02b89856`; decisões consultadas: D-007, D-009 (`docs/DECISIONS.md`), carta `docs/COORDINATOR.md` §13, inventário sanitizado COORD-04/P3B.3 e validação QA-P9.
**Arquivos próprios:** `.opencode/scripts/coord_model_policy.py`, `.opencode/scripts/test_coord_model_policy.py`, este documento.

## 1. Contrato

`recommend(inventory, policy, mission, evidence) -> Recommendation`:

- **Descoberta por inventário injetado** — nenhuma consulta a serviço, nenhuma leitura de catálogo em tempo de execução, nenhum `time.time()`.
- **Identidade exata** — `providerID + model id + variant opcional` (`Model.key`). Nenhuma recomendação por nome parcial.
- **Categorias explícitas** — `free` (gratuito), `included` (franquia OpenCode Go — não equivale necessariamente a cobrança extra), `additional` (consumo extraordinário), `unknown`. A categoria vem do campo do inventário: **um nome contendo "free" não prova gratuidade**.
- **Preferência Free First / 1M** — gratuitos primeiro; entre gratuitos, contextos conhecidos `>= 1_000_000` têm prioridade; contexto desconhecido é classificado explicitamente e abaixo dos conhecidos, nunca presumido como 1M.
- **Requisitos da missão** — contexto mínimo, ferramentas, modalidades e capacidades devem estar comprovados no inventário; ausência = exclusão com código de diagnóstico (`TOOLS_UNVERIFIED`, `MODALITY_UNVERIFIED`, `CAPABILITY_UNVERIFIED`).
- **Disponibilidade** — `unavailable` e `at_limit` excluídos; `unknown` excluído por padrão (política pode optar por considerá-lo, sempre com código explícito). Nenhuma certeza fabricada.
- **Cooldown** — só aplicado quando há `cooldown_until` **e** evidência injetada com `observed_at`; sem evidência, `COOLDOWN_UNVERIFIED` — nenhum horário de renovação inventado.
- **Rotação** — se o modelo atual estiver indisponível ou for o primeiro elegível e houver outro gratuito, recomenda o próximo gratuito com `ROTATION`/`CURRENT_MODEL_ROTATED`.
- **Escalonamento pago** — bloqueado por padrão (`BLOCKED_PAID`, `escalation_blocked=True`); só aparece como recomendação se `paid_escalation_authorized=True` **e** `paid_authorization_ref` preenchido. A recomendação nunca executa o escalonamento.
- **Missão UNKNOWN** — `BLOCKED_UNKNOWN_MISSION`, sem modelo, sem alternativas, sem escalonamento.
- **Separação seleção ≠ execução** — `Recommendation.executed` é sempre `False`; não existe função de troca de sessão, POST, credencial ou chamada de modelo neste módulo.
- **Justificativa determinística** — códigos estáveis ordenados, plus `describe()` com `status | model | category | reasons | executed=False`. Mesmo inventário e política produzem a mesma saída, independentemente da ordem de entrada (empate resolvido pela chave exata).

## 2. Uso previsto (benchmark futuro)

O módulo é uma interface pura para futura avaliação offline de políticas: inventários sintéticos, missões com requisitos explícitos e evidências injetadas podem ser comparados por `describe()` sem executar inferência. Nenhum benchmark, prompt, provedor ou sessão é manipulado nesta entrega. `ASSISTED` e `DELEGATED` permanecem desativados.

## 3. Testes (host-only, 15/15 PASS)

`python3 -m unittest discover -s .opencode/scripts -p 'test_coord_model_policy.py'`:

1. gratuitos de 1M preferidos; 2. gratuito de contexto menor; 3. modelo indisponível; 4. limite atingido; 5. contexto desconhecido (com e sem mínimo); 6. custo desconhecido apesar do nome "free"; 7. modelo pago bloqueado × autorizado; 8. franquia Go distinta de gratuito; 9. empate determinístico; 10. ausência de opção gratuita; 11. missão UNKNOWN; 12. alteração dinâmica do inventário; 13. cooldown com/sem evidência; 14. ferramentas/modalidade/capacidade; 15. rotação e `executed=False`.

## 4. Limitações

- Inventário é **entrada**: a política não verifica se os dados recebidos são verdadeiros; isso depende da proveniência do chamador (evidência COORD-04/P3B.3 e QA-P9).
- Listar um modelo ou registrar custo 0 não prova capacidade efetiva de inferência — nenhum modelo foi executado.
- Franquia Go vs. cobrança adicional não é observável localmente (`usage/limits` nulo; `cost=0` não prova ausência de consumo) — o motor **não** decide essa distinção, apenas respeita a categoria injetada.
- Disponibilidade real e limites só podem ser confirmados por evidência externa ao motor; sem evidência, falha segura.
- Nenhuma troca de modelo de sessão, A–E, Android, credencial, schema, merge, release, bump, LLM pago ou gratuito, ADB ou HealthAgentForms.
