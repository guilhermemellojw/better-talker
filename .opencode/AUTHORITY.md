# COORD-06A — fundação determinística de autorização e revogação

**Estado:** proposta em PR Draft · **base:** `02b89856837d03cbaa11d2f30d5931b4d7e43bd3`.

Este documento descreve o validador **offline e puro** em
`.opencode/scripts/coord_authority.py`. Ele **não concede autoridade**, não
despacha, não transmite, não altera sessões, não lê credenciais e não executa
operação externa. Produz apenas uma decisão determinística.

## 1. Contratos implementados

| Contrato | Implementação |
| --- | --- |
| Missão/operação | `Mission` (IDs, projeto, repositório, identidade canônica, payload, destinatário, HEAD, escopo, modelo, nº de transmissões) |
| Autorização | `Authorization` (vinculação integral + janela UTC + consumo + referência de aprovação externa) |
| Evidência externa | `ExternalAuthorityEvidence` (referência, digest, origem, aprovador, observação) |
| Revogação | `Revocation` indexada ao **digest canônico imutável** da autorização (não ao `reference` mutável), com autoridade, digest, instante e motivo |
| Confiança | `TrustContract` injetado: projeto, repositório, modo, HEADs, modelos (com `#variant`), escopos, agentes, limite de transmissões, `authority_pins`, `allowed_approvers` e `max_evidence_age_seconds` |
| Estado operacional | `OperationState`: `NOT_DISPATCHED`, `TRANSMITTED`, `RUNNING`, `UNKNOWN`, `TERMINAL` |
| Resultado | `Decision` com `eligible`, `blockers` ordenados, `required_evidence`, estado, referência e notas |

## 2. Correspondências validadas

Projeto e repositório; mission ID e operation ID; identidade canônica da
missão; hash do payload; destinatário (agente + sessão + worktree + instância);
HEAD autorizado; escopo; modelo; limite de transmissões; e janela temporal UTC.

Bloqueios: ausente, vencida, ainda não válida, revogada, divergente, já
consumida, sem evidência externa suficiente, evidência divergente/não fixada,
limite atingido, modo e estado incompatíveis, além de `INVALID_INPUT`.

## 3. Autoridade ≠ estrutura ≠ auto-relato

- A validação estrutural é independente da comprovação de autoridade.
- Um `bool`, uma string ou um relatório autodeclarado **nunca** prova
  aprovação humana: `self_declared_approved` é aceito apenas para ser
  **ignorado** (nota `SELF_DECLARED_APPROVAL_IGNORED`).
- Só é aceita evidência com `source=EXTERNAL_ARTIFACT`, referência igual à
  fixada no contrato e digest igual ao pin. O módulo apenas compara valores
  injetados: **não gera assinatura, não verifica criptografia e não cria
  autoridade**.
- Revogação válida bloqueia novas operações da autorização atingida. O
  resultado registra `NO_RETROACTIVE_CANCELLATION`: não se presume cancelamento
  retroativo nem interrupção de trabalho em andamento.

## 4. Modos

- `SHADOW`: bloqueio absoluto de transmissão produtiva (`MODE_SHADOW_BLOCKED`).
- `ASSISTED` e `DELEGATED`: permanecem **desativados** (`MODE_DISABLED`); a
  inclusão existe apenas para tornar explícito o congelamento.
- `UNKNOWN` bloqueia reenvio (`STATE_UNKNOWN_BLOCKED`), conforme
  `docs/COORDINATOR.md` §6.1 e §14.

## 5. Ponto de integração futuro (não implementado)

O validador deve ser chamado **somente leitura**, antes de qualquer despacho,
pela camada que hoje executa o checklist de `docs/COORDINATOR.md` §6:

1. construir `Mission` a partir do envelope COORD-04 (`coord_communication.py`),
   sem reutilizar nem duplicar `PilotApproval` (`coord_pilot.py`);
2. injetar `TrustContract` com decisões registradas em `docs/DECISIONS.md`,
   HEAD/escopo/modelo vigentes e pins de autoridade externa;
3. fornecer `ExternalAuthorityEvidence` obtida de fonte externa ao agente;
4. chamar `ca.evaluate(...)`; qualquer bloqueio ⇒ não despachar;
5. registrar a decisão no ledger operacional (futuro) — **este módulo não
   persiste nem consome a autorização**.

A reserva atômica, o consumo, o ledger persistente, a reconciliação de
sessão e a revogação distribuída permanecem fora deste componente.

## 6. Limitações

- Não há enforcement operacional: nada aqui altera permissões, sessões,
  configuração do OpenCode ou roteamento de mensagens.
- Não há ledger: `consumed`, `transmissions` e `operation_state` são **consultivos** (`CONSUMPTION_ADVISORY`, `TRANSMISSION_ADVISORY`, `STATE_ADVISORY`); `NOT_DISPATCHED` com transmissões > 0 é bloqueado por `TRANSMISSION_STATE_INCONSISTENT`. `revocations` também são fornecidas pelo chamador.
- Não há autoridade criptográfica: digests pinned são âncora explícita, não
  assinatura verificada.
- Não há garantia `exactly-once`; IDs apenas correlacionam.
- Nenhum teste aqui prova comportamento em Android, dispositivo, rede ou
  serviço OpenCode. A execução física e a homologação pertencem ao Agente D.

## 7. Testes

`cd .opencode/scripts && python3 -m unittest -v test_coord_authority` — **22 testes**
host-only, sem Gradle, ADB, LLM, credenciais ou I/O de rede (redes bloqueadas com
`unshare -rn` na execução de QA). Cobertura: os 16 testes P1 preservados e seis
regressões P2 — evidência inalterada com material alterado, revogação imutável por
digest, aprovador fora da allowlist, evidência obsoleta, consumo/estado/transmissões
consultivos, `#variant` de modelo compatível com #71 e validação de sessão/worktree.

## 8. COORD-06A/P2 — compatibilidade com #70/#71 e pendências

- `#70` (ledger, integrado na main): estados canônicos `PROPOSED → AUTHORIZED → DISPATCHING → DELIVERED → RUNNING → COMPLETED/FAILED/UNKNOWN` são mapeados por `OperationState`; **`UNKNOWN` bloqueia reenvio** e operações já transmitidas não são tratadas como retroativamente canceladas. O ledger não persiste consumo/limite — este componente trata esses campos como consultivos e não duplica ledger.
- `#71` (política de modelos, integrada na main): `allowed_models` aceita variante explícita (`provider/model#variant`) como identidade distinta, alinhada a `canonical_model_ref`; nenhum registro de custo é reimplementado aqui.
- `#64` (comunicação, ainda Draft): apenas leitura. `MissionEnvelope`, `canonical_mission_identity` e `PilotApproval` permanecem fora deste módulo; a integração futura deve mapear `mission_identity` e `approval_reference` sem duplicar `PilotApproval` nem ativar transporte real.

**Limites remanescentes:** SHADOW é o único modo admitido (`eligible=True` é inalcançável por desenho); nenhuma autoridade externa é contatada ou autenticada criptograficamente; pins/evidência/aprovador são injetados e a âncora externa permanece responsabilidade da coordenação; nenhum enforcement operacional, sessão, POST, LLM, Android ou permissão real foi alterado.
