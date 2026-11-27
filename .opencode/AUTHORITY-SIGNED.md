# COORD-06B/P1 — verificação offline de autoridade assinada

**Estado:** experimental, somente verificação (verifier-only), em PR Draft
dependente do #72. Não concede autoridade, não assina, não persiste consumo,
não altera ledger e não executa transporte.

| Item | Valor |
| --- | --- |
| Base certificada | `cfc498e0b6f2fb10ff1a4b93c451b048230d95db` (HEAD do PR #72) |
| Branch | `feat/coord06b-signed-authority` |
| PR dependente | Draft, base `feat/coord06-authorization-gate` (PR #72); URL no PR |
| Modo | `SHADOW` apenas |
| Transporte | sintético, nenhum POST/sessão/rede/LLM |
| PR #64 / P14A | não alterado |
| Ledger #70 | não alterado; contrato de integração apenas documentado |
| Beta | não interferida |

## 1. Escopo e separação de planos

O componente responde a uma pergunta limitada: **dada uma assinatura OpenSSH
destacada e uma allowlist pública, o envelope assinado corresponde exatamente
ao material esperado da operação?**

Ele não responde “pode despachar?”. A decisão operacional pertence ao ledger
#70 e ao executor P14B. A separação é:

| Plano | Responsável | Este componente |
| --- | --- | --- |
| Integridade material | envelope canônico + digest | recalcula payload e digest |
| Autenticidade | assinatura OpenSSH do humano | verifica assinatura/allowlist/fingerprint |
| Vinculação à operação | adaptador P14B (`ExpectedOperation`) | compara missão, payload, alvo, modelo, custo, HEAD e limites |
| Revogação | declaração assinada + trust policy | verifica, não persiste |
| Consumo/replay | ledger #70 | **não implementado aqui** |
| Enforcement/transporte | executor P14B + ledger | **não implementado aqui** |

## 2. Fronteira de confiança — leia antes de usar

A verificação criptográfica comprovada nos testes **não** é uma fronteira de
confiança implantada. No ambiente atual o agente e o mantenedor compartilham o
mesmo UID. Se a chave privada, a allowlist, o verifier ou o ledger estiverem
graváveis/legíveis pelo agente, ele pode:

- adicionar a própria chave à allowlist;
- substituir o verifier;
- escrever no SQLite do ledger;
- assinar um envelope diferente do revisado.

Portanto, para o piloto real, uma destas separações é obrigatória:

1. chave humana em hardware token com presença física; ou
2. chave em UID/conta separada, fora do alcance do agente; ou
3. signer service em UID separado, com confirmação humana e `SO_PEERCRED`.

O ledger/verifier também precisa rodar em UID/serviço separado, com o agente
tendo apenas uma API de solicitação — sem escrita no SQLite/trust store. Sem
isso, o máximo obtido é integridade material, não autoridade humana provada.

## 3. Envelope canônico

`SignedAuthorizationEnvelope` contém:

```json
{
  "schema": "BT-COORD-AUTHORIZATION-V1",
  "authorization_id": "AUTH-...",
  "approval_reference": "APP-...",
  "approver_id": "maintainer",
  "approver_key_fingerprint": "SHA256:...",
  "trust_policy_sha256": "<sha256 da allowlist>",
  "mission_id": "...",
  "operation_id": "...",
  "project_id": "better-talker",
  "repository_id": "guilhermemellojw/better-talker",
  "coordinator_id": "...",
  "mission_identity": "<sha256 recalculado por #64>",
  "payload": {"...": "..."},
  "payload_sha256": "<sha256 do payload efetivo>",
  "target": ["agent", "ses-...", "/worktree/absoluto", "instance"],
  "model": "provider/model#variant",
  "scope": "...",
  "reviewed_head_sha": "<40 hex>",
  "operational_digests": {"coord_communication.py": "<sha256>"},
  "max_transmissions": 1,
  "cost_ceiling_usd": "0",
  "free_only": true,
  "issued_at": "...Z",
  "expires_at": "...Z",
  "nonce": "...",
  "mode": "SHADOW"
}
```

Regras:

- `canonical_json` ordena chaves, usa separadores `,`/`:` e UTF-8.
- `payload_sha256` é **recalculado** do payload; um hash declarado que não
  corresponde é rejeitado (`INVALID_ENVELOPE`).
- `mission_identity` é **opaca** neste módulo. Deve ser recalculada pelo
  adaptador P14B a partir de `MissionEnvelope`/`coord_communication` e passada
  em `ExpectedOperation`. Não há segunda função de identidade canônica.
- `cost_ceiling_usd` é normalizado (`0`, `1.5`, `0.01`), sem ambiguidade de
  zeros à direita; `free_only=true` exige custo zero/ausente.
- `mode` diferente de `SHADOW` é rejeitado.
- A assinatura cobre `DOMAIN + canonical_json(envelope.canonical_material())`,
  com `DOMAIN = b"BT-COORD-AUTHORIZATION-V1\n"`.
- O digest usado pela revogação/ledger é
  `SHA-256(DOMAIN + canonical_json(material))`.

`authorization_digest` é imutável e não depende de `approval_reference`
mutável; a revogação é indexada por ele.

## 4. Vinculação à operação esperada

`ExpectedOperation` é obrigatório em `verify_signed_envelope`. Ele é o
resultado do recálculo feito pelo chamador confiável (adaptador P14B/ledger):

- IDs de missão/operação, projeto, repositório, coordenador;
- `mission_identity` recalculada em #64;
- **payload efetivo** (o módulo recalcula `payload_sha256`, não aceita um hash
  declarado pelo chamador);
- destinatário (`agent`, `session`, `worktree`, `instance`);
- modelo exato (incluindo `#variant`), escopo;
- `reviewed_head_sha` e `operational_digests`;
- `max_transmissions`, `cost_ceiling_usd`, `free_only`.

Qualquer divergência produz `OPERATION_MISMATCH` com a lista de campos. Uma
assinatura válida isolada **não** é autorização: sem `ExpectedOperation` a
chamada falha por `TypeError`.

## 5. Assinatura OpenSSH

O humano assina exatamente os bytes canônicos, fora do processo do agente:

```bash
ssh-keygen -Y sign -f <chave-humana> -n bt-coord-authorization-v1 envelope.bin
ssh-keygen -Y verify -f allowed_signers -I <approver_id> \
  -n bt-coord-authorization-v1 -s envelope.bin.sig < envelope.bin
```

- A chave privada nunca entra no ambiente do agente.
- `allowed_signers` é público, revisado e protegido contra escrita pelo
  agente.
- `ApproverKey` fixa `approver_id`, fingerprint SHA256, chave pública e janela
  UTC; a verificação recalcula o fingerprint da chave e o compara ao envelope.
- A allowlist tem digest canônico; o envelope pina esse digest. Qualquer
  mudança na allowlist (inclusive revogação de chave) invalida envelopes
  antigos: fail-closed.
- Testes usam somente chaves sintéticas descartáveis em diretório temporário;
  nenhuma chave pessoal é lida.

A assinatura é verificada com `ssh-keygen -Y verify`; o módulo não tem função
de assinatura e o teste 24 verifica que não há `-Y sign` no código.

## 6. Revogação e expiração

- `SignedRevocation` é assinada com namespace separado
  (`bt-coord-revocation-v1` + domínio próprio) e indexada ao
  `authorization_digest`.
- A revogação inclui `approver_id`, fingerprint, `trust_policy_sha256`,
  `revoked_at`, motivo e nonce.
- `verify_signed_revocation` verifica assinatura, allowlist, fingerprint,
  janela da chave e retorna `effective=true` somente quando `revoked_at <= now`.
- Uma revogação inválida/desconhecida **não** bloqueia o envelope; não é
  aceita como revogação. O ledger deve persistir apenas revogações verificadas.
- A revogação de chave é feita por `ApproverKey.revoked_at` + digest da
  allowlist; envelopes antigos falham por `TRUST_POLICY_MISMATCH` e novos
  envelopes assinados pela chave revogada falham por `APPROVER_KEY_INACTIVE`.
- `issued_at`/`expires_at` são assinados; o verificador usa o relógio do
  serviço (`now`) e falha fechado para janela invertida, futura ou expirada.

## 7. API e códigos

| Símbolo | Função |
| --- | --- |
| `ApproverKey` / `ApproverAllowlist` | trust store público, fingerprint, janela, digest |
| `SignedAuthorizationEnvelope` | envelope canônico, validação, digest, compatibilidade estrutural com #72 |
| `ExpectedOperation` | material recalculado pelo chamador confiável |
| `canonical_envelope_bytes` / `authorization_digest` | bytes assinados e digest |
| `verify_signed_envelope` | assinatura + allowlist + validade + vínculo + revogação |
| `SignedRevocation` / `verify_signed_revocation` | revogação assinada por digest |
| `public_key_fingerprint` | fingerprint SHA256 de chave pública, sem chave privada |

Códigos principais: `OK`, `INVALID_ENVELOPE`, `TRUST_POLICY_MISMATCH`,
`APPROVER_KEY_UNKNOWN`, `FINGERPRINT_MISMATCH`, `APPROVER_KEY_INACTIVE`,
`AUTHORIZATION_NOT_YET_VALID`, `AUTHORIZATION_EXPIRED`, `SIGNATURE_INVALID`,
`OPERATION_MISMATCH`, `AUTHORIZATION_REVOKED` e, para revogação,
`REVOCATION_SIGNATURE_INVALID`, `REVOCATION_NOT_YET`,
`REVOCATION_EVIDENCE`/`INVALID_REVOCATION`.

## 8. Limitações deliberadas

`VerifiedEnvelope` registra explicitamente:

```text
enforcement = "not_implemented"
consumption_persisted = False
replay_protected = False
```

- **Não há consumo persistido.** A mesma assinatura pode ser verificada duas
  vezes; o teste `test_replay_is_not_protected_offline` documenta isso.
- **Não há proteção completa contra replay.** Ela depende do ledger #70:
  `authorization_digest` único, reserva atômica, consumo e estado.
- **Não há enforcement.** O resultado não altera permissões, sessões,
  transporte ou modelo; é insumo para o ledger/executor.
- **Não há ledger.** Revogações, allowlist e resultados não são persistidos.
- **Não há geração/gestão de chaves nem assinatura.**
- **Não há segunda identidade canônica.** `mission_identity` vem de #64.
- **Não há política de custo real.** `free_only`/teto são vinculados ao
  envelope; a decisão de autorizar custo é humana/ledger.
- **Mesmo UID não é fronteira de confiança.** Ver §2.
- **Nenhum teste prova operação real** em Android, rede, OpenCode ou LLM.

## 9. Integração futura com o ledger #70 (não implementada aqui)

O ledger continua sendo a única autoridade operacional. Para integrar, ele
precisa persistir e transacionar:

- `authorization_digest`, envelope assinado, `approver_id`, fingerprint,
  `trust_policy_sha256`, `issued_at`, `expires_at`, `max_transmissions`,
  `cost_ceiling_usd`, `free_only`, `mission_identity`, `payload_sha256`,
  target, modelo, HEAD e digests operacionais;
- `authorize` idempotente apenas para material idêntico; divergência rejeita;
- `reserve_dispatch` com `BEGIN IMMEDIATE`, unicidade global por
  `(project, agent, session)`, consumo atômico e `lease_id`;
- `assert_dispatchable` imediatamente antes do POST, revalidando estado,
  reserva, consumo, revogação e expiração;
- `UNKNOWN` preservando reserva, sem reenvio automático; reconciliação apenas
  com evidência externa;
- revogação por digest, com auditoria; `COMPLETED` somente com evidência.

O verificador deste PR não substitui esse contrato; ele apenas produz o
material verificado que o ledger deve consultar. Nenhuma alteração foi feita
em `.opencode/scripts/coord_ledger.py` (#70).

## 10. Dependências para o P14B

1. **P14A final** (Agente A, PR #64): identidade canônica/payload recalculados,
   portas de ledger/modelo, ausência de autoridade local, SHADOW/sintético.
2. **Trust root**: chave humana e isolamento (hardware, UID separado ou signer
   service); allowlist pública protegida; ledger/verifier fora do alcance do
   agente.
3. **#72**: extensão deste contrato em rodada autorizada (o PR #72 original
   permanece preservado).
4. **#70**: schema/API de autorização assinada, consumo, limites, reserva
   global, `assert_dispatchable`, UNKNOWN; migração versionada.
5. **P14B adapter**: `AuthorityPort`, `LedgerPort`, `ModelPolicyPort`,
   `ExpectedOperation` a partir de #64, gate antes do POST.
6. **QA independente** e matriz integrada; CI Python host-only com rede
   bloqueada.

## 11. Evidência de testes

Host-only, rede bloqueada (`unshare -rn`):

```bash
cd .opencode/scripts
unshare -rn python3 -m unittest test_coord_authority test_coord_authority_signed -v
# 22 + 25 = 47 OK
unshare -rn python3 test_supervisor.py
# 167 passed, 0 failed
```

Fail-first: a árvore em `cfc498e0` não contém `coord_authority_signed`; o mesmo
arquivo de testes falha na importação (`ModuleNotFoundError`) antes da
extensão. Após a implementação, 25/25 passam. Os 22 testes originais do #72
permanecem verdes.

Cenários cobertos: assinatura ausente/inválida/chave não autorizada,
fingerprint divergente, adulteração de missão/payload/alvo/modelo/custo/HEAD/
digests/limites/janela, expiração, janela futura, chave inativa/revogada,
trust policy divergente, identidade de missão incompatível, revogação válida/
inválida/desconhecida/futura/outro digest, separação de namespace, SHADOW,
limites/custo, compatibilidade estrutural com #72, ausência de assinatura/
persistência/rede/ledger e replay explicitamente não protegido.

## 12. Decisões solicitadas

1. Aprovar a direção do verificador offline e sua integração futura ao #70,
   mantendo o PR #72 original preservado.
2. Confirmar que a âncora humana será hardware token, UID separado ou signer
   service; sem isso não há fronteira de confiança implantada.
3. Autorizar rodadas separadas para #72/#70 (assinatura/consumo/ledger) e a
   integração P14B após o P14A final.
4. Confirmar `SHADOW`/sintético até autorização humana específica do primeiro
   piloto real; nenhuma operação real é autorizada por este documento.
