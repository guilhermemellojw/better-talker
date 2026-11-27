# P14B — parecer focal do adapter #81 e integração consolidada (Agente A)

**Adapter auditado:** PR #81 `feat/p14b-authority-adapter` @ `b8b35882fbc296d7bddf2e2070f83b17b9b31a2d` (base `27506ea3`).
**Integração:** branch `feat/coord06b-p14b-synthetic` (PR #64 P14B em andamento), merge convencional do adapter e do CI #79.

> Nenhum PR de origem (#64/#77/#80/#81/#79) foi alterado; nenhum merge à main, credencial real, chave real, migração, sudo, LLM, sessão real ou transporte real. Chaves OpenSSH sintéticas temporárias; transporte simulado.

---

## A. Reprodução focal (HEAD `b8b35882`, rede bloqueada, HOME/TMP isolados)

| Suíte | Resultado |
|---|---|
| `test_coord_p14b_authority.py` | **14/14 OK** |
| `unittest discover` | **182/182 OK** (98 ledger + 14 adapter + 70 política #71) |
| `test_supervisor.py` | **167/167 OK** |
| Testes originais #77 sobre as cópias verbatim | **47/47 OK** |

Guarda de rede `ci_netguard` do CI #79; nenhum `sudo` na reprodução local. Hash dos logs e METHOD abaixo conferem com a evidência publicada.

## B. Verificação material independente

- **Assinatura OpenSSH efetiva:** o verificador usa `ssh-keygen -Y verify` com `-I`, `-n <namespace>` e allowlist pinada (`coord_authority_signed.py:584-617`). Assinatura válida passa; forjada e namespace incorreto falham fechado.
- **Envelope V2 vinculante:** `BT-COORD-AUTHORIZATION-V2`, domínio canônico próprio, exige `acceptance_criteria`, `environment`, modelo, alvo, HEAD, digests, limites, custo e nonce; `from_mapping` rejeita V1 com `INSUFFICIENT_ENVELOPE` — sem downgrade.
- **Identidade canônica recalculada:** `cc.canonical_mission_identity()` é recomputada do `MissionEnvelope` e comparada ao envelope (`coord_p14b_authority.py:593,637`); divergência bloqueia.
- **Atestação terminal e revogação:** namespace de reconciliação separado; vincula receipt/report/evidence/outcome; revogação valida assinatura, allowlist, fingerprint, trust policy, janela e `revoked_at <= now`. Replay pertence ao ledger e é bloqueado.
- **Divergências:** material (payload, critérios, ambiente, modelo), identidade, assinatura, namespace e trust policy — todas rejeitadas.
- **Separação:** caminho humano aceita somente `SHADOW`; `FIXTURE`/`SYNTHETIC` recusados pelo adapter humano, reservados ao verificador sintético separado. `SyntheticAuthorization` do P14A não é autoridade #77.

## C. Identidade dos arquivos dependentes

| Arquivo | Origem | SHA-256 conferido |
|---|---|---|
| `coord_authority.py` | #77 `47c7cc87` | `3435f9bd…387faf7f` |
| `coord_authority_signed.py` | #77 `47c7cc87` | `467fb880…089575c1` |
| `coord_communication.py` | #64 `4ed209f9` | `e972edec…14eb01df4d` |
| `coord_p14b_authority.py` | #81 `b8b35882` | `582be6fe…805b34142a8` |

As cópias temporárias foram substituídas pela composição dos commits originais por merges convencionais; os digests acima são idênticos nos HEADs de origem e na integração. Nenhum conteúdo foi reescrito.

## D. Integração consolidada

- Merge de `b8b35882` (#81) e do CI #79 atualizado (`da916cde`) em `feat/coord06b-p14b-synthetic`; conflito de baseline resolvido para os números consolidados.
- CLI usa `SignedOperationStore` (ledger 1.1 + autoridade assinada), não cache JSON legado.
- Política #71 permanece delegada ao motor `coord_model_policy`; nenhuma regra duplicada.
- **Baseline consolidada recalculada:** `unittest discover=506`, `supervisor=167`, **total=673** (inclui 14 do adapter e 15 de integração P14B).
- Jornada completa com chave sintética e transporte simulado: `authorize → reserve → assert → evidence → reconcile → COMPLETED`; 15/15 testes P14B, incluindo assinatura efetiva, payload/modelo divergentes, allowlist não confiável, ata adulterada e caminho legado fechado.
- Interrupção pós-POST sem receipt, seguida de expiração ou revogação: estado `UNKNOWN` preservado, reserva mantida, sem reenvio (`test_interrupted_post_then_*`).

## E. Limitações

Fronteira de confiança implantada (chave privada/allowlist/verificador/ledger fora do alcance do agente, identidade de consumidor) permanece fora desta missão; sem ela a prova é de integridade material, não de autoridade humana. Nenhuma `PilotApproval` real, nenhuma transmissão real e nenhum piloto operacional foram autorizados.

## F. Veredito focal

Adapter #81: **parecer focal favorável no material verificado**, sem bloqueadores reproduzidos. Integração P14B consolidada em `feat/coord06b-p14b-synthetic` @ `90cc4b83…`, com 673 testes host-only e CI #79 integrado. Recomenda-se auditoria independente final da fronteira de confiança e do LEDGER 1.1 em ambiente controlado antes de qualquer operação real. PRs de origem permanecem Draft/intactos.
