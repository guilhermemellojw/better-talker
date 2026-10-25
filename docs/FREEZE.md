# Itens congelados / decisões pendentes

**Atualizado:** 2026-10-25 · **Build de referência:** v2.7.4 (versionCode 24) · **Fase 2:** concluída (`506f813`) · **Fase 3:** concluída (`ebdcd92`)

> “Congelado” = não reintroduzir sem decisão explícita. “Decisão pendente” = o
> código/schema pode existir, mas o produto ainda não fechou o comportamento.

## Congelados / removidos

| Item | Status | Observação |
|---|---|---|
| Web v1 (Capacitor/Vite) | **REMOVIDO do produto** | Resíduos de build em `public/`, `tools/e2e-*.cjs`, `vite.config.ts`, `tsconfig*.json` são legado congelado; não entram no APK. Remover ou arquivar é limpeza pendente. |
| Multi-LLM (Gemini, Groq, Qwen, Gemma, fallback, circuit breaker) | **REMOVIDO** | DeepSeek-only (BYOD) + motor determinístico offline. |
| Kokoro TTS / bench de vozes | **REMOVIDO** | Piper “Jeff” 100% offline nos assets. |
| Testes de vozes obsoletos (`KokoroVoicesTest`, `KokoroBenchTest`, `PiperVoicesTest`) | **REMOVIDO** | — |
| `activity_main.xml` | **REMOVIDO** | Compose é a UI. |
| Consideração V&M (“O que você diria?”) | **REMOVIDO do fluxo de colagem** | Rejeitada com aviso; não cria nota (decisão 1b, 2026-10-25). |
| Logs `hs_err_pid*.log` / `replay_pid*.log` | **FORA DO VERSIONAMENTO** | Cobertos pelo `.gitignore`; não commitar. |

## Resolvidos na Fase 2 — 2026-10-25

Commit `506f813` · validação no A34 (S-34 novo com 30 min; Tesouros novo com 10 min) ·
**2273 testes JVM verdes**.

| Item | Decisão/ação |
|---|---|
| Título da nota V&M com tempo | Preview e nota criada sem `(N min)`; o tempo fica nos cards. |
| Minutos de INTRO/CONCLUSÃO no S-34 | O total declarado é respeitado; BODYs normalizados (30 = 1+5+11+7+5+1). |
| Contador de palavras do S-34 | Separa `developed` de `outline`: “0 desenvolvidas · N no esboço” + dica “desenvolva este ponto”. |
| Entrada do Copilot no S-34 | ✨ movido para o cabeçalho do card ativo (S-34 longo e V&M). |
| Rodapé/ruído no parser S-34 | `A SER ABRANGIDO EM N MINUTOS` vira total declarado; `N.° …-T …` é descartado. |
| Espaços internos em palavras do S-34 | `stripXml` remove tags inline sem inserir espaço; blocos viram quebra de linha. O caso “Jeov de de deá” do A34 era draft/edição, não parser. |

## Resolvidos na Fase 3 — 2026-10-25

Commit `ebdcd92` · validação no A34 (discurso 10 min/3 pontos; demonstração livre
4 min) · **2293 testes JVM verdes**.

| Item | Decisão/ação |
|---|---|
| Criar do zero | `StartFromScratchSheet` (ModalBottomSheet) com título, tipo, duração, pontos e prévia; botão desabilitado se inválido. |
| Estrutura sugerida | `StructureSuggestion`: Intro 1 + N pontos + Conclusão 1, distribuição `base + remainder`; demo livre = 1 BODY. |
| Criação atômica | `createNoteFromStructure` usa `createPastedNote`; seções nascem vazias. |
| Demonstração livre | Novo `FREE_DEMO`, 1 BODY, sem S-38; persona neutra `FREE_DEMO_SCRIPTWRITER`/`FREE_DEMO_CHAT_HINT`. |
| Parte da reunião | Fora do “Criar do zero”; continua via colagem V&M. |
| FREE_DEMO no app | Validator (single body), editor, Hub (badge “Demonstração”), Tribuna e Ensaio (turnos). |

## Decisões pendentes (não remover ainda)

| Item | Estado atual | O que falta decidir |
|---|---|---|
| Folders/tags/cores/pin na Home | Schema Room dormente; UI usa lista simples | **Reativar, remover de vez ou manter dormente?** Não apagar schema/migração sem decisão. |
| Backup / chave DeepSeek | `allowBackup` + DataStore em texto claro | `allowBackup=false`/`dataExtractionRules` excluindo DB/DataStore; Keystore para a chave. |
| Keystore de release | Release assina com a debug keystore | Gerar keystore de release fora do repositório antes de distribuir. |
| Sync de seções/subpontos | P0 de 2026-10-19; correções posteriores | Revisar identidade/reconciliação multi-device antes de prometer sync. |

## Regra de processo

Antes de remover qualquer item “congelado” ou mexer em decisão pendente:

1. Registrar a decisão (data + motivo) neste arquivo ou no relatório da sessão.
2. Garantir teste JVM que trave o comportamento escolhido.
3. Validar no aparelho de referência (Redmi 9 / A34 / tablet) quando a mudança for de UX.
