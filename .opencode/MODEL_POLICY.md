# MODEL_POLICY — Free First / 1M Preferred (COORD-05B/P2.1)

**Estado:** proposta em PR Draft; não integrada a sessões, pilotos ou ao PR #64.
**Escopo:** motor determinístico de **seleção e recomendação** de modelos a partir de inventário, política, missão, disponibilidade e benchmark injetados.
**Fora de escopo:** trocar modelo, criar/alterar sessão, enviar prompt, chamar LLM/API/rede, ler credenciais, ADB/Android, merge, release, bump, ASSISTED/DELEGATED, HealthAgentForms.

> **Free First:** modelos gratuitos são a primeira escolha; o contexto de 1M ou superior é preferido; ao atingir limite/indisponibilidade, recomenda-se outro gratuito. DeepSeek Flash (incluindo a variante Go) é fallback econômico **somente com autorização específica**; consumo adicional não autorizado é bloqueado.

## 1. Entradas

O motor é puro: recebe tudo por injeção. Nenhum dado é buscado por conta própria.

### 1.1 Inventário

```json
{
  "source": "sanitized-p9",
  "observed_at": "2026-11-26T01:00:00Z",
  "version": "p9",
  "models": [
    {
      "provider_id": "opencode",
      "model_id": "hy4-flash-free",
      "variant": "default",
      "context_window_tokens": 1000000,
      "tools": true,
      "modalities": ["text"],
      "capabilities": ["reasoning"],
      "cost": {
        "category": "free",
        "source": "catalog",
        "observed_at": "2026-11-26T01:00:00Z",
        "input_usd_per_million": "0",
        "output_usd_per_million": "0"
      }
    }
  ]
}
```

- `provider_id`, `model_id` e `variant` formam a identidade exata; não há normalização por nome.
- `variant: "none"` é o marcador explícito de ausência e é **canonicalizado internamente para ausência**. Igualdade, hash, ordenação, evidência de disponibilidade, autorização e detecção de duplicidade usam a mesma identidade. Variante ausente nunca vira `#default`; variantes explícitas diferentes (por exemplo `#high` e `#default`) permanecem distintas.
- `context_window_tokens: null` significa **desconhecido**, não 1M.
- `tools: null` / `modalities: null` / `capabilities: null` significam **desconhecido**, não ausência.
- `cost.category` é obrigatório para classificar custo; a ausência vira `unknown`. O nome do modelo **nunca** é evidência de gratuidade.
- `cost.source` e `cost.observed_at` registram a procedência; não provam disponibilidade futura.

### 1.2 Política

```json
{
  "free_first": true,
  "preferred_context_tokens": 1000000,
  "allow_unknown_context": true,
  "allow_unknown_availability": false,
  "allow_included_franchise": true,
  "allow_additional_consumption": false,
  "rotation_enabled": true,
  "coordination_mode": "SHADOW",
  "benchmark_suite": "coord05",
  "paid_authorization": null
}
```

- `free_first` deve ser `true`; a política não aceita inverter a prioridade.
- `allow_unknown_context`: se `true`, contexto desconhecido pode ser considerado com incerteza explícita; se a missão exigir contexto mínimo, ele é excluído de qualquer forma.
- `allow_unknown_availability`: padrão `false`. Com `false`, ausência de evidência resulta em `INSUFFICIENT_EVIDENCE`, não em recomendação fabricada.
- `allow_included_franchise`: permite **propor** modelo incluído na franquia Go, mas ainda exige autorização específica para selecionar.
- `allow_additional_consumption`: padrão `false`; consumo adicional é bloqueado. Mesmo com `true`, exige autorização válida.
- `rotation_enabled`: padrão `true`. Com `false`, preservação e seleção inicial continuam permitidas, mas **nenhuma substituição automática** ocorre em caminho gratuito ou pago; o resultado é `NEEDS_AUTHORIZATION` com alternativa consultiva, `selected: null` e nenhuma intenção de troca.
- `coordination_mode`: apenas `SHADOW`; `ASSISTED` e `DELEGATED` são rejeitados.
- `paid_authorization`: ver §5.

### 1.3 Missão e requisitos

```json
{
  "mission_id": "coord05b-p1",
  "state": "PLANNED",
  "scope": "coord05b:test",
  "requirements": {
    "min_context_tokens": 1000000,
    "requires_tools": true,
    "required_modalities": ["text"],
    "required_capabilities": ["reasoning"]
  },
  "current_model": null,
  "estimated_extra_cost_usd": null
}
```

- `state` é obrigatório. Ausência ou valor inválido falha fechado na entrada (`ValueError`); não há presunção de `PLANNED`.
- `scope` é necessário para qualquer autorização paga: sem escopo de missão não há vínculo comparável e a autorização é recusada.
- `state: UNKNOWN` ou `delivery_state: UNKNOWN|INDETERMINATE` bloqueiam qualquer recomendação/reenvio.
- Missão `RUNNING`, `COMPLETED` ou `FAILED` pode receber parecer consultivo, mas **não** produz `SessionChangeIntent` operacional.
- Requisitos de contexto, ferramentas, modalidade e capacidade são filtros rígidos, não preferências.
- `estimated_extra_cost_usd` é necessário para validar autorização de fallback pago; sem ele, a autorização não é validada.

### 1.4 Disponibilidade

```json
[
  {
    "provider_id": "opencode",
    "model_id": "hy4-flash-free",
    "variant": "default",
    "status": "available",
    "observed_at": "2026-11-26T01:00:00Z",
    "source": "catalog-probe"
  }
]
```

Status: `available`, `unavailable`, `limits_reached`, `unknown`.

- A evidência mais recente por modelo vence; empate temporal com status divergente vira `unknown` (conflito), não certeza.
- `limits_reached` exige `cooldown_until` explícito para indicar janela; sem ele, o modelo fica bloqueado e **nenhum horário de renovação é inventado**.
- `cooldown_until` no passado **não** torna o modelo automaticamente disponível: sem nova evidência, ele volta a `unknown`.
- Evidência futura em relação a `evaluated_at` não é usada como certeza.

### 1.5 Benchmark (futuro)

```json
[
  {
    "provider_id": "opencode",
    "model_id": "hy4-flash-free",
    "variant": "default",
    "suite": "coord05",
    "score": "0.91",
    "sample_size": 20,
    "status": "verified",
    "source": "future-benchmark",
    "observed_at": "2026-11-26T01:00:00Z"
  }
]
```

- Apenas `status: verified`, `suite` igual a `policy.benchmark_suite` e observação não futura entram no desempate.
- `status: unverified` é ignorado e registrado como incerteza.
- O motor **não executa inferência**; um futuro runner pode fornecer as observações via `BenchmarkProvider`, mas este módulo não chama modelos.

## 2. Categorias de custo

| Categoria | Significado | Seleção |
|---|---|---|
| `free` | custo nominal 0 no catálogo/evidência | permitida como primeira escolha |
| `included` | incluído na franquia OpenCode Go; não é necessariamente cobrança adicional | somente com `allow_included_franchise` **e** autorização válida; sem autorização, `NEEDS_AUTHORIZATION` |
| `additional` | consumo extraordinário/cobrança adicional | bloqueado por padrão; exige `allow_additional_consumption` e autorização válida |
| `unknown` | custo não comprovado | nunca tratado como gratuito; não selecionado; pode gerar `INSUFFICIENT_EVIDENCE` |

**Não inferir gratuidade pelo nome `free`.** Um modelo chamado `mega-free` com categoria `additional` ou `unknown` não é gratuito. A categoria explícita é a única fonte.

Um registro `free` com preço positivo é contraditório e é rejeitado. Categoria declarada no nível do modelo e no objeto `cost` também precisa coincidir; divergência rejeita o inventário. Autorização paga só aceita `included` e `additional`; `free` e `unknown` são categorias inválidas para consumo pago.

## 3. Ranking determinístico

Entre candidatos elegíveis, a ordem é:

1. **Custo:** `free` > `included` > `additional` (adicional só entra se autorizado).
2. **Disponibilidade:** `available` > `unknown` (quando a política permite desconhecido). Indisponível/limite ativo é excluído.
3. **Contexto preferido:** conhecido e `>= preferred_context_tokens` (1M) antes de conhecido abaixo do limite.
4. **Contexto conhecido:** conhecido antes de desconhecido.
5. **Benchmark verificado:** maior score; ausência perde para score verificado; unverified é ignorado.
6. **Identidade exata:** `provider_id`, `model_id`, `variant` em ordem lexical, para desempate estável.

Consequências deliberadas:

- Um gratuito de 1M vence um gratuito menor.
- Um **modelo atual** gratuito, elegível e disponível é preservado mesmo que outro gratuito tenha contexto maior.
- Disponibilidade desconhecida do modelo atual não é causa confirmada de substituição: com `allow_unknown_availability=true`, o atual é preservado com incerteza; sem essa permissão, o resultado é consultivo (`INSUFFICIENT_EVIDENCE`), sem troca. O mesmo vale quando o modelo atual não está no inventário fornecido.
- Um gratuito **confirmado disponível** vence um gratuito de 1M com disponibilidade desconhecida quando ainda não há modelo atual a preservar. A certeza de disponibilidade prevalece sobre a preferência de contexto.
- Contexto desconhecido não é contado como 1M; fica abaixo de contexto conhecido.
- Dois gratuitos de 1M sem benchmark são desempatados lexicalmente; a ordem é estável entre execuções.
- Se o modelo atual está disponível e é o melhor, a decisão é `KEEP_CURRENT`; se há indisponibilidade, limite ou inadequação confirmada, recomenda-se outro gratuito.

## 4. Desconhecidos e indisponibilidade

| Situação | Resultado |
|---|---|
| contexto desconhecido, sem requisito mínimo | incerteza `context_unknown`; pode ser elegível se `allow_unknown_context`; nunca é tratado como 1M |
| contexto desconhecido, com `min_context_tokens` | excluído (`context_unknown_required`) |
| disponibilidade sem evidência | `INSUFFICIENT_EVIDENCE` por padrão; com `allow_unknown_availability=true`, recomendação inicial com incerteza |
| disponibilidade desconhecida do **modelo atual** | não substitui: preserva com incerteza se permitido; caso contrário, parecer consultivo sem seleção e sem intenção de troca |
| `unavailable` confirmado | excluído; recomenda-se outro gratuito, se a rotação estiver habilitada |
| `limits_reached` com cooldown futuro | excluído (`cooldown_active`); recomenda-se outro gratuito, se a rotação estiver habilitada |
| `limits_reached` sem cooldown | excluído; nenhuma renovação inventada |
| cooldown vencido sem nova evidência | `unknown`; não vira `available` automaticamente |
| custo desconhecido | não é gratuito; excluído; pode gerar `INSUFFICIENT_EVIDENCE` |
| evidência conflitante | `unknown`; pode gerar `INSUFFICIENT_EVIDENCE` |

## 5. Escalonamento pago e autorização

Sem autorização válida, **nenhum** modelo `included` ou `additional` é selecionado. O resultado é `NEEDS_AUTHORIZATION`, com `selected: null`, `fallback_candidate` exato e `requires_authorization: true`.

A autorização é específica e limitada:

```json
{
  "authorized": true,
  "authorized_by": "maintainer",
  "scope": "coord05b:test",
  "mission_id": "coord05b-p1",
  "categories": ["included", "additional"],
  "model_refs": [
    {"provider_id": "opencode-go", "model_id": "deepseek-v4.1-flash", "variant": "default"}
  ],
  "max_cost_usd": "0.50",
  "expires_at": "2026-11-27T00:00:00Z",
  "evidence": "authorization-reference",
  "external_validation": {
    "authority": "maintainer",
    "reference": "decision:D-007",
    "scope": "coord05b:test",
    "mission_id": "coord05b-p1",
    "validated_at": "2026-11-26T10:00:00Z"
  }
}
```

Validação: autorização concedida; `mission_id` presente e igual à missão; escopo da missão presente e igual ao escopo da autorização; validação externa estruturada presente e vinculada a autoridade, escopo e missão; categoria coberta; identidade exata coberta; não expirada; `max_cost_usd` presente e `estimated_extra_cost_usd` da missão `<=` teto. Caso contrário, não há seleção.

- A validação externa é um **registro estruturado**, não uma frase ou referência textual isolada. O motor verifica o vínculo contratual, mas **não autentica criptograficamente o emissor** nem contata a autoridade; essa limitação é explícita.
- `included` não é chamado de gratuito.
- `additional` permanece bloqueado por padrão.
- O motor não mede franquia, tokens ou billing; apenas aplica a política e a evidência injetada.

## 6. Missão UNKNOWN e missão em execução

Se `mission.state == UNKNOWN` ou `delivery_state == UNKNOWN|INDETERMINATE`:

- decisão `MISSION_UNKNOWN`;
- `selected: null`;
- motivo `mission_state_unknown` ou `delivery_unknown`;
- `no_resend_until_reconciled`;
- nenhuma recomendação de reenvio, nenhuma troca de modelo.

Se a missão está `RUNNING`, `COMPLETED` ou `FAILED`, o motor não emite `SessionChangeIntent`: a saída é apenas consultiva e registra `change_intent_suppressed_mission_state`. Nenhuma troca operacional é proposta enquanto a entrega não estiver reconciliada.

## 7. Seleção ≠ execução ≠ troca de sessão

O resultado é sempre:

- `selection_only: true`;
- `execution_performed: false`;
- `session_change_performed: false`;
- `session_change_intent.executed: false`;
- `session_change_intent.requires_separate_authorization: true`.

O motor **não** cria, altera, ocupa ou troca sessão. Qualquer troca efetiva exige um executor separado, autorização específica e os controles do COORD-04/PR #64; este módulo não integra esse caminho.

## 8. Determinismo e justificativa

- Sem rede, LLM, API, credenciais, aleatoriedade ou estado global mutável.
- `evaluated_at` é injetável; a mesma entrada + o mesmo instante produz o mesmo resultado.
- Ordenação estável; desempate lexical pela identidade exata.
- `explanation` lista decisão, selecionado, ranking e exclusões com códigos determinísticos.
- `reason_codes`, `uncertainties`, `ranked` e `excluded` são ordenados.

## 9. Decisões

| Decisão | Significado |
|---|---|
| `RECOMMEND_FREE` | gratuito elegível selecionado; em missão operacional, troca de sessão é apenas uma intenção separada |
| `KEEP_CURRENT` | modelo atual preservado (disponível ou desconhecido permitido); nenhuma mudança |
| `RECOMMEND_AUTHORIZED_FALLBACK` | fallback incluído/adicional selecionado com autorização contratualmente válida |
| `NEEDS_AUTHORIZATION` | existe fallback elegível, mas falta autorização válida ou a rotação está desativada; nada selecionado |
| `NO_ELIGIBLE_FREE` | nenhum gratuito/fallback elegível por razões confirmadas |
| `INSUFFICIENT_EVIDENCE` | ausência/conflito de contexto, disponibilidade ou custo; inclui alternativa consultiva sem substituição confirmada |
| `MISSION_UNKNOWN` | missão UNKNOWN; não recomendar reenvio |

## 10. Testes

`python3 .opencode/scripts/test_coord_model_policy.py -v`

Cobertura atual (58 testes host-only, rede/LLM desativados):

- gratuitos de 1M × gratuitos menores;
- indisponibilidade confirmada e troca para outro gratuito;
- limites atingidos e cooldown com/sem renovação;
- limite sem cooldown, sem horário de renovação inventado;
- contexto desconhecido, com e sem requisito mínimo;
- custo desconhecido e nome `free` sem evidência;
- pagos adicionais e franquia Go;
- autorização válida, expirada, acima do teto, missão/modelo/categoria/escopo exatos;
- validação externa estruturada ausente e categorias pagas inválidas;
- identidade canônica `none`/ausente em igualdade, hash, evidência, autorização e duplicidade;
- missão sem estado, estado inválido, `RUNNING` sem intenção operacional;
- rotação desativada nos caminhos gratuito e pago, com seleção inicial ainda permitida;
- disponibilidade desconhecida do modelo atual e modelo atual ausente do inventário sem substituição confirmada;
- revalidação de objetos tipados: duplicidade canônica, estado inválido, contexto inválido e política incompatível;
- empates lexicais e benchmark verificado × unverified;
- ausência de opção gratuita;
- missão UNKNOWN e `delivery_state UNKNOWN`;
- alteração dinâmica do inventário sem cache;
- ferramentas, modalidades e capacidades, inclusive desconhecidas;
- seleção separada de execução/troca de sessão;
- serialização JSON, duplicidade e validação de política.

## 11. Referências e fronteiras

- `docs/COORDINATOR.md` §13 — Free First, três categorias de custo, disponibilidade não permanente.
- `docs/DECISIONS.md` D-007 e D-009 — Free First, DeepSeek Flash fallback, identidade ≠ modelo.
- COORD-04/P3B.3 — inventário sanitizado: 18 modelos, `opencode-go/hungerg-free#default` ausente, modelos gratuitos listados não testados; nenhum LLM executado.
- QA-P9 — 18 modelos (11 `opencode/*`, 7 `groq/*`), `hy4-flash-free` e `seaglass-alpha-free` listados, custo 0; listado ≠ capacidade comprovada.
- **PR #64 permanece congelado.** Este módulo não altera `coord_pilot*.py`, `COMMUNICATION.md`, `PILOT.md` ou os testes do piloto. Integração futura exige autorização e revisão separadas.
- **ASSISTED/DELEGATED permanecem desativados.** O motor só opera em `SHADOW` e não despacha missões.

## 12. Histórico desta branch

- `270b9a85` — versão compacta inicial publicada no PR Draft #71 (15 testes).
- `697d055b`/`4e6986f7` — ampliação de autorização/benchmark, continuidade Free First, estado explícito, referência canônica e 37 testes offline; preservados no histórico.
- Revisão P2.1 — fecha divergências entre código e relatório: identidade canônica interna, autorização vinculada a missão/escopo/validação externa, estado obrigatório, rotação em todos os caminhos e revalidação de objetos tipados. **58 testes offline PASS**; PR #71 permanece Draft; nenhum merge.

## 13. Limitações explícitas

- O inventário é injetado; este módulo não consulta `/api/model`.
- A disponibilidade é evidência declarada; não há sonda de rede nem certeza de disponibilidade futura.
- Custo/franquia não são medidos; `cost: 0` não prova consumo zero.
- Capacidades são campos declarados; não há teste de inferência real.
- Benchmark é interface futura; sem observação verificada, não há score.
- A validação externa é declarada e vinculada por contrato; o motor não autentica o emissor nem verifica assinatura, credencial ou canal externo.
- Objetos tipados são revalidados nas fronteiras `Inventory`, `Policy` e `Mission`; isso não transforma entrada declarada em evidência física.
- Não há exactly-once, troca de sessão, transmissão, ledger ou autorização automática.

## 14. COORD-05B/P2.1 — divergências código × relatório

Revisão focal sobre o HEAD `4e6986f71f1d36b4ff7a70c3f185d391baea321e` do PR #71. Delta exclusivo em motor, testes e este documento; nenhum outro arquivo, nenhuma mudança em PR #64/#70, governança compartilhada, sessões, configuração real, Android ou credenciais.

**Fail-first no HEAD exato:** 19 falhas em 57 testes — 18 regressões novas e a expectativa existente de disponibilidade desconhecida do modelo atual, que o contrato F4 tornou explicitamente incompatível. A verificação anterior de estado inválido e a seleção inicial paga já passavam e permaneceram verdes. Uma sonda focal adicional demonstrou que o modelo atual ausente do inventário ainda era substituído; após a correção, a suíte final tem **58/58 PASS**, host-only, sem rede ou LLM.

- **F1 — identidade canônica real:** `variant="none"` é canonicalizado para `None` no próprio `ModelRef`, não apenas em `__str__`. Igualdade, hash, `sort_key`, evidência de disponibilidade, autorização e duplicidade usam a mesma identidade. Variantes explícitas diferentes permanecem distintas; ausente não vira `#default`.
- **F2 — autorização e custo:** `mission_id` é obrigatório para autorização paga e comparado à missão; `scope` da missão e da autorização são vinculados e comparados; validação externa exige registro estruturado (`authority`, `reference`, `scope`, `mission_id`, `validated_at`), não referência textual isolada. `FREE` com preço positivo, categoria contraditória no mesmo modelo e categorias `free`/`unknown` em autorização paga são rejeitados. Validade, modelo, categoria e teto financeiro continuam aplicados; não há faturamento.
- **F3 — estado obrigatório:** `Mission.from_value()` não presume mais `PLANNED`; estado ausente ou inválido falha na entrada. `UNKNOWN` continua bloqueando recomendação e reenvio. `RUNNING`, `COMPLETED`, `FAILED` e entrega indeterminada não geram `SessionChangeIntent`; a saída permanece consultiva e registra `change_intent_suppressed_mission_state`.
- **F4 — rotação em todos os caminhos:** `rotation_enabled=false` bloqueia substituição no caminho gratuito e no fallback pago; preservação e seleção inicial continuam permitidas. Disponibilidade desconhecida do modelo atual — ou ausência dele no inventário — não é causa confirmada de substituição: se a disponibilidade desconhecida for permitida pela política, o atual é preservado com incerteza; caso contrário, há alternativa consultiva (`INSUFFICIENT_EVIDENCE`, `selected: null`) e nenhuma intenção de troca. O mesmo contrato vale para fallback pago.
- **F5 — validação de objetos tipados:** `Inventory`, `Policy` e `Mission` já instanciados são revalidados pelo mesmo caminho de parsing, em vez de retornados diretamente. Sondas focais cobrem duplicidade canônica, estado inválido, contexto inválido e política incompatível. Não há framework genérico de validação.

### Correspondência documental com o ledger COORD-05A (PR #70, sem integração)

| Ledger PR #70 | Estado neste motor | Efeito |
|---|---|---|
| `PROPOSED` | `PLANNED` | pode recomendar; intenção separada permitida |
| `AUTHORIZED` | `READY` | pode recomendar; intenção separada permitida |
| `DISPATCHING`, `DELIVERED`, `RUNNING` | `RUNNING` | parecer consultivo; sem intenção operacional |
| `COMPLETED` | `COMPLETED` | terminal; sem intenção operacional |
| `FAILED` | `FAILED` | terminal; sem intenção operacional |
| `UNKNOWN` | `UNKNOWN` | `MISSION_UNKNOWN`; sem reenvio nem troca |

A correspondência é apenas documental. Nenhum código, esquema ou estado do PR #70 é importado, alterado ou integrado.

Invariantes mantidas: `executed=False`, `selection_only=True`, `execution_performed=False`, `session_change_performed=False`. ASSISTED e DELEGATED continuam desativados. Aguardando certificação independente; nenhum merge.

## 15. COORD-05B/P3 — vinculação integral da autorização financeira

Correção focal do P1 do parecer QA-COORD-71 (`7193729654`). Delta restrito aos três arquivos próprios do PR #71.

**Fail-first:** no HEAD certificado `8fb26b64`, a mesma `external_validation` acompanhava `max_cost_usd=0.50` e `max_cost_usd=5.00`; o segundo contrato era aceito (`RECOMMEND_AUTHORIZED_FALLBACK`). Após a correção, a alteração material com prova inalterada resulta em `NEEDS_AUTHORIZATION / authorization_external_material_mismatch`.

**Material canônico:** `canonical_authorization_material()` serializa de forma determinística (JSON com chaves ordenadas) autoridade, referência, missão, escopo, categorias autorizadas, modelos/variantes canônicos, teto financeiro, validade e condições de custo. `authorization_material_sha256()` é o digest SHA-256 dessa serialização — **integridade contratual, não assinatura e não autoridade autenticada**.

**Evidência externa:** `ExternalValidation` passa a exigir `material_sha256` (digest do material integral) e pode declarar `estimated_cost_ceiling_usd`. O motor compara ambos e rejeita: prova divergente (`authorization_external_material_mismatch`), referência divergente, autoridade/escopo/missão divergentes, validade futura, custo acima do teto aprovado ou acima do teto externo declarado. O custo estimado efetivamente usado é registrado em `authorization_estimated_cost_usd` na recomendação e em `as_dict()`, permanecendo verificável na decisão.

**Preservação:** os 58 testes certificados permanecem intactos como métodos; apenas o helper de fixture foi estendido para construir a prova vinculada. Cinco regressões P3 cobrem teto alterado com prova inalterada, adulteração de cada campo material, teto do custo estimado, digest ≠ assinatura e objeto tipado que não contorna a vinculação. Total: **63 testes host-only, rede bloqueada, sem LLM**.

**Integração futura com PR #72 (não implementada):** o validador `coord_authority.py` injeta um `TrustContract` com pins de autoridade externa e produz uma decisão consultiva sem efeito. Antes de qualquer uso operacional pago, o motor deve consumir essa decisão — verificando que o pin corresponde ao `material_sha256` aqui definido e que a operação está elegível — em vez de criar outra âncora de confiança, outro ledger ou autenticar criptograficamente por conta própria. Nenhuma infraestrutura de confiança externa foi duplicada neste PR.

**Limitações:** digest não é assinatura; nenhuma autoridade externa é contatada; inventário/disponibilidade/custo permanecem entradas declaradas; execução, troca de sessão, faturamento e transmissão continuam desativados; ciência da disponibilidade real depende de D. Sem merge, release, bump, provider, schema, ADB, sessão, credencial ou HealthAgentForms.
