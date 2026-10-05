# Validação Física F20-F1 — A34 (2026-10-05)

## Ambiente
- **Device:** SM-A346M (a34x), ADB wireless (conectado)
- **APK:** HEAD `63c9533` — `assembleDebug` ✓ + `adb install -r` Success
- **Gemma:** presente — `gemma-4-E2B-it.litertlm` (2.588.147.712 bytes) em
  `/sdcard/Android/data/com.bettertalker.app/files/models/`
- **Chave DeepSeek:** ausente — `deepseek_api_key` não existe no DataStore
  (`settings.preferences_pb`)

## Resultado: BLOQUEIO RESOLVIDO (Bug #1 corrigido e verificado no device)

### Bug #1 (bloqueador) — ModelScreen sem rolagem; card DeepSeek inacessível
- **Sintoma:** em `Modelo IA`, o card “DeepSeek — chat de raciocínio (BYOD)”
  começava no fim da tela; o campo “Chave DeepSeek (sk-…)” e o botão
  “Salvar chave” ficavam **abaixo da dobra**. Era impossível adicionar a chave.
- **Evidência original (A34, 1080×2340):**
  - `uiautomator dump`: **0** nós `scrollable="true"` na tela de Modelo IA.
  - Swipe para cima **não movia** o conteúdo (card em `y=1910` após o gesto).
  - Os nós “Chave DeepSeek” e “Salvar chave” **não existiam** na árvore de acessibilidade.
- **Causa:** `ui/aimodel/ModelScreen.kt` — `Column` raiz sem `verticalScroll`.
- **Correção aplicada (fora da validação, autorizada):** commit `c80c3a6` —
  `Modifier.verticalScroll(rememberScrollState())` na `Column` raiz.
- **Verificação pós-fix no A34:** `scrollable="true"` = **1** nó; após swipe,
  “Chave DeepSeek” e “Salvar chave” **visíveis** (1 nó cada); screenshot confirma
  o card DeepSeek completo (campo + olho + botão + seletor flash/v4-pro).

### Observação #2 — gate de RAM exibe 7,3 GB (< 8 GB) no A34
- O card Gemma mostra “RAM do aparelho: 7,3 GB — abaixo do mínimo de 8 GB”, mas
  o modelo está presente (via `adb push`) e o app segue. O gate
  `LlmConfig.MIN_RAM_BYTES = 8 GB` só afeta a UI de download — registrar para
  revisão (mínimo de 8 GB vs. RAM total reportada de 7,3 GB no A34).

### Observação #3 — cosmético
- O chip “deepseek-v4-pro” quebra em duas linhas na largura do A34. Sem impacto funcional.

### Bug #4 (bloqueador do DeepSeek) — chave salva não é uma chave de API válida
- **Sintoma:** “Testar conexão” no card DeepSeek retorna **Erro** (não “Online”).
- **Evidência (API real, requisição no formato exato do app, com a chave do aparelho — valor nunca impresso):**
  - `POST https://api.deepseek.com/v1/chat/completions` → **HTTP 401**
    `Authentication Fails (auth header format should be Bearer sk-...)`
  - Impressão digital do valor salvo: `len=38`, **não começa com `sk-`**, termina em `0d72`
    (o próprio erro da API mascarou `****0d72`).
- **Conclusão:** o valor salvo em `deepseek_api_key` não é uma chave da API DeepSeek
  (chaves do platform começam com `sk-`). Provavelmente veio do app consumidor
  (`com.deepseek.chat` está instalado no aparelho) ou foi colado incompleto.
- **Ação para destravar:** gerar uma chave em `platform.deepseek.com` (com créditos) e
  salvar de novo no card DeepSeek. Sem isso, os Blocos 2/3/5/6/7 (DeepSeek) não rodam.

### Observação #5 — rótulo do status na sonda
- A sonda exibiu “Erro de conexão” para um 401 de autenticação; o esperado pelo código é
  “Erro de autenticação”. Reconfirmar na próxima sessão (a re-execução limpa foi interrompida
  pela queda do ADB). Baixa severidade (UX do indicador).

### Incidente — ADB caiu no meio
- Durante a re-execução limpa da sonda, o ADB wireless caiu (`waiting for device`;
  `adb devices` vazio). **Validação interrompida conforme a regra** (sem tentar contornar).

## Resultados por bloco (sessão de 05/10, pós-correção)

### Bloco 1 — Import e indexação: **PASSOU**
- Import de `s34-35.docx` (S-34 real do aparelho) via chat → “Anexar esboço” → Importar.
- Draft detectado: **5 seções** com minutos (5/4/9/8/4) e título correto.
- “Esboço vinculado ✓ (5 seções)”; banco: `speech_sections` com INTRO + 5 BODY e **28 subpontos**.
- Observação: **tabelas `s34_*` continuam vazias** (pipeline estruturado F19 não populado neste import) — impacta rota oratória/estrutural.
- Tempo de indexação: < 5 s (draft criado ~3 s após a seleção). Nº de passages novos: 0 (S-34 vira outline; não é publicação indexada).

### Bloco 2 — Chat DeepSeek (streaming): **PASSOU**
- `llm_provider=deepseek`; streaming visível (parcial na tela a ~3 s).
- Troca 2: usuário 1281.925 → resposta 1285.393 = **3,47 s** total; resposta coerente com o ponto 3 do esboço (qualidade 4/5).
- Gate atuou: “⚠️ Revise: aspas sem fonte…” (14:27:06) e remoção de 2 trechos (14:30:20).
- Falha intermitente: 14:38:03 `ProviderError INVALID_RESPONSE` (“Não consegui gerar”) — ver Bug #6.

### Bloco 3 — Gate de alucinação: **PARCIAL**
- Aviso visível on-device (aspas sem fonte) e remoção pelo verificador: **observados**.
- Citação inventada (“Malaquias 99:99”): o **modelo recusou** inventar e ofereceu alternativas reais **dentro de `〈sugestão〉`**; sem aviso porque as refs têm apoio no acervo.
- “📖 No acervo: <trecho>” **não observável**: com provedor ativo, o comando de referências vai ao modelo; os cards de refs só rodam no caminho determinístico (ver Obs #7).
- Texto limpo: sem avisos (resposta da metáfora).

### Bloco 4 — Modo Criação: **PASSOU (caso 1)**
- “Invente uma metáfora…” → resposta com `〈sugestão〉…〈/sugestão〉`; UI exibiu **💡 Sugestão criativa** e escondeu as tags.
- Casos 2/3 (número/versículo inventado dentro de criação): não exercitados no device (o modelo recusou inventar); cobertos por testes JVM (`SuggestionGateTest`).

### Bloco 5 — Fallback (auto): **NÃO EXECUTADO**
- Provider estava explícito (`deepseek`); o teste exige trocar para “Automático” + offline (automação de airplane-mode não executada nesta sessão).

### Bloco 6 — Proposta F5 + Criação: **PARCIAL**
- “Criar proposta” sem bloco em foco orienta corretamente (“Selecione um trecho ou abra um bloco…”).
- Após focar um tópico no editor, o toque em “Criar proposta” **não gerou** o cartão (sem log de geração) — não concluído nesta sessão.

### Bloco 7 — Estabilidade: **NÃO EXECUTADO**
- App sobreviveu a toda a navegação da sessão sem crash/ANR; force-stop e as 10 interações não foram executados.

### Bug #6 (DeepSeek, intermitente) — reasoning pode esvaziar a resposta
- 14:38:03: `ProviderError INVALID_RESPONSE` com chave válida → “Não consegui gerar a resposta agora.”
- A sonda “Testar conexão” usa `max_tokens=1` e **sempre** falha em modelo de raciocínio (content vazio) → rótulo “Erro de conexão” com chave válida.
- Sugestão (não aplicada): orçamento mínimo na sonda (ex.: 32+) e/ou tratar `reasoning_content`/`finish_reason=length` sem content como falha explícita de orçamento.

### Observação #7 — cards de referência inacessíveis com provedor ativo
- Com rota remota/local ativa, comandos de referências são respondidos pelo modelo; o card com “📖 No acervo” só aparece no caminho determinístico.

### Observação #8 — `s34_*` vazias após import
- O import gerou outline/sections/subpoints, mas `s34_outlines/sections/subsections/references` = 0 — a rota estruturada/oratória fica degradada para esta nota.

## Blocos (estado anterior)
- (histórico) Blocos 1–7 não executados antes da correção do Bug #1.

## State
- Bug #1 corrigido em `c80c3a6` (fora da validação) e verificado no device.
- Bug #4 resolvido pelo dono (chave regenerada, `sk-`, HTTP 200).
- Bugs #6 e Observações #7/#8 registrados; aguardam decisão.
- Screenshots/logs de apoio em `/tmp/opencode/` (não versionados).
