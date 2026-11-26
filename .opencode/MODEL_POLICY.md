# MODEL_POLICY — Free First / 1M Preferred (COORD-05B/P1)

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
- `coordination_mode`: apenas `SHADOW`; `ASSISTED` e `DELEGATED` são rejeitados.
- `paid_authorization`: ver §5.

### 1.3 Missão e requisitos

```json
{
  "mission_id": "coord05b-p1",
  "state": "PLANNED",
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

- `state: UNKNOWN` ou `delivery_state: UNKNOWN` bloqueiam qualquer recomendação/reenvio.
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
- Um gratuito **confirmado disponível** vence um gratuito de 1M com disponibilidade desconhecida, quando a política permite desconhecido. A certeza de disponibilidade prevalece sobre a preferência de contexto.
- Contexto desconhecido não é contado como 1M; fica abaixo de contexto conhecido.
- Dois gratuitos de 1M sem benchmark são desempatados lexicalmente; a ordem é estável entre execuções.
- Se o modelo atual está disponível e é o melhor, a decisão é `KEEP_CURRENT`; se está indisponível, recomenda-se outro gratuito.

## 4. Desconhecidos e indisponibilidade

| Situação | Resultado |
|---|---|
| contexto desconhecido, sem requisito mínimo | incerteza `context_unknown`; pode ser elegível se `allow_unknown_context`; nunca é tratado como 1M |
| contexto desconhecido, com `min_context_tokens` | excluído (`context_unknown_required`) |
| disponibilidade sem evidência | `INSUFFICIENT_EVIDENCE` por padrão; com `allow_unknown_availability=true`, recomendação com incerteza |
| `unavailable` confirmado | excluído; recomenda-se outro gratuito |
| `limits_reached` com cooldown futuro | excluído (`cooldown_active`); recomenda-se outro gratuito |
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
  "scope": "coord05b:fallback",
  "mission_id": "coord05b-p1",
  "categories": ["included", "additional"],
  "model_refs": [
    {"provider_id": "opencode-go", "model_id": "deepseek-v4.1-flash", "variant": "default"}
  ],
  "max_cost_usd": "0.50",
  "expires_at": "2026-11-27T00:00:00Z",
  "evidence": "authorization-reference"
}
```

Validação: autorização concedida, missão compatível, categoria coberta, identidade exata coberta, não expirada, `max_cost_usd` presente e `estimated_extra_cost_usd` da missão `<=` teto. Caso contrário, não há seleção.

- `included` não é chamado de gratuito.
- `additional` permanece bloqueado por padrão.
- O motor não mede franquia, tokens ou billing; apenas aplica a política e a evidência injetada.

## 6. Missão UNKNOWN

Se `mission.state == UNKNOWN` ou `delivery_state == UNKNOWN`:

- decisão `MISSION_UNKNOWN`;
- `selected: null`;
- motivo `mission_state_unknown` ou `delivery_unknown`;
- `no_resend_until_reconciled`;
- nenhuma recomendação de reenvio, nenhuma troca de modelo.

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
| `RECOMMEND_FREE` | gratuito elegível selecionado; troca de sessão é apenas uma intenção separada |
| `KEEP_CURRENT` | modelo atual disponível e melhor; nenhuma mudança |
| `RECOMMEND_AUTHORIZED_FALLBACK` | fallback incluído/adicional selecionado com autorização válida |
| `NEEDS_AUTHORIZATION` | existe fallback elegível, mas falta autorização válida; nada selecionado |
| `NO_ELIGIBLE_FREE` | nenhum gratuito/fallback elegível por razões confirmadas |
| `INSUFFICIENT_EVIDENCE` | ausência/conflito de contexto, disponibilidade ou custo; nada fabricado |
| `MISSION_UNKNOWN` | missão UNKNOWN; não recomendar reenvio |

## 10. Testes

`python3 .opencode/scripts/test_coord_model_policy.py -v`

Cobertura (32 testes):

- gratuitos de 1M × gratuitos menores;
- indisponibilidade confirmada e troca para outro gratuito;
- limites atingidos e cooldown com/sem renovação;
- limite sem cooldown, sem horário de renovação inventado;
- contexto desconhecido, com e sem requisito mínimo;
- custo desconhecido e nome `free` sem evidência;
- pagos adicionais e franquia Go;
- autorização válida, expirada, acima do teto, missão/modelo/categoria exatos;
- empates lexicais e benchmark verificado × unverified;
- ausência de opção gratuita;
- missão UNKNOWN e `delivery_state UNKNOWN`;
- alteração dinâmica do inventário sem cache;
- ferramentas, modalidades e capacidades, inclusive desconhecidas;
- disponibilidade desconhecida, inclusive do modelo atual;
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
- Revisão atual — mantém o commit anterior no histórico e amplia o contrato: autorização paga exata (missão/modelo/categoria/validade/teto), fallback Go/adicional separado de gratuito, estado de missão UNKNOWN, preservação do modelo atual disponível, benchmark verificado como desempate, interface de indisponibilidade/cooldown e 32 testes offline. O PR #71 permanece Draft; nenhum merge.

## 13. Limitações explícitas

- O inventário é injetado; este módulo não consulta `/api/model`.
- A disponibilidade é evidência declarada; não há sonda de rede nem certeza de disponibilidade futura.
- Custo/franquia não são medidos; `cost: 0` não prova consumo zero.
- Capacidades são campos declarados; não há teste de inferência real.
- Benchmark é interface futura; sem observação verificada, não há score.
- Não há exactly-once, troca de sessão, transmissão, ledger ou autorização automática.

## 14. COORD-05B/P2 — reconciliação de continuidade, estado e custo

Revisão focal sobre o HEAD ampliado do PR #71 (o commit compacto `270b9a85` permanece no histórico). Delta desta etapa: **motor + testes + este documento**, nenhum outro arquivo, nenhuma mudança em PR #64/#70, governança compartilhada, sessões, configuração real ou Android. 37 testes offline PASS; fail-first executado com ImportError para `canonical_model_ref` e falha de rotação sem justificativa antes das correções.

- **F1 — rotação somente justificada:** modelo gratuito atual, disponível e adequado é preservado (`KEEP_CURRENT`, motivo `kept_current`) mesmo quando outro gratuito tem contexto maior — sem redução desnecessária de contexto; rotação ocorre somente quando o atual está indisponível, em limite ou inadequado; `policy.rotation_enabled=false` produz `NEEDS_AUTHORIZATION` com `fallback_candidate`, `selected=None` e nenhum `SessionChangeIntent`. Missão UNKNOWN continua bloqueando qualquer troca.
- **F2 — estado real da missão:** `MissionState` explícito, compatível com o futuro ledger COORD-05A; `mission_id` é identificador único e imutável e **não** é usado como estado; estado ausente/inválido falha de forma segura (`MISSION_UNKNOWN`/`INSUFFICIENT_EVIDENCE`), sem reenvio ou rotação.
- **F3 — franquia e autorização:** `free` confirmado ≠ `included` na franquia ≠ `additional` ≠ custo desconhecido. Modelo incluído não é automaticamente autorizado: sem `PaidAuthorization` válida resulta `NEEDS_AUTHORIZATION`. A autorização exige `authorized_by`, `scope`, `mission_id`, `model_refs`, `categories`, `max_cost_usd`, `expires_at` e `evidence` vinculados; uma referência textual isolada é rejeitada (`ValueError`). Piloto de custo extraordinário zero não recomenda consumo incluído sem política compatível; nenhum faturamento é implementado.
- **F4 — inventário e evidências:** identidades duplicadas e custo/disponibilidade contraditórios são rejeitados na origem; categoria inválida, contexto negativo/incompleto e evidência desconhecida não habilitam a entrada mais permissiva; disponibilidade desconhecida só aparece como incerteza/consultiva, nunca como confirmada.
- **F5 — identidade canônica:** `canonical_model_ref()` adota `provider/id`, `variant="none"` sem sufixo, variante ausente sem `#default` implícito e variante explícita distinta preservada; contrato documentado do PR #64, sem importar código daquele PR.
- **F6 — justificativa e 1M:** Free First / preferência por >=1M preservados em seleção inicial e substituição justificada; alternativas condicionais explícitas; sem opção permitida → solicitação de autorização; janela de contexto não é capacidade comprovada; nenhuma renovação de cota é inventada; `decision` registra manutenção, seleção, rotação ou espera de autorização.

`executed=False`/`selection_only=True`/`execution_performed=False`/`session_change_performed=False` permanecem invariáveis. ASSISTED e DELEGATED continuam desativados. Aguardando recertificação independente.
