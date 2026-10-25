# Itens congelados / decisões pendentes

**Atualizado:** 2026-10-25 · **Build de referência:** v2.7.4 (versionCode 24)

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

## Decisões pendentes (não remover ainda)

| Item | Estado atual | O que falta decidir |
|---|---|---|
| Folders/tags/cores/pin na Home | Schema Room dormente; UI usa lista simples | **Reativar, remover de vez ou manter dormente?** Não apagar schema/migração sem decisão. |
| Título da nota V&M com tempo | Preview mostra sem `(10 min)`; nota criada recebe com `(10 min)` (`PasteNoteBuilder` VmTalk) | Manter o tempo no título da nota ou alinhar ao preview? |
| Minutos de INTRO/CONCLUSÃO no S-34 | Cada uma recebe 1 min; S-34 de 30 min vira nota de 32 min | Consumir 0, redistribuir ou aceitar o acréscimo? |
| Contador de palavras do S-34 | “0 palavras” quando só há `outlineText` (subpontos) | Contar subpontos/`outlineText` ou manter a métrica só de texto desenvolvido? |
| Entrada do Copilot no S-34 | ✨ só aparece no card ativo; subponto S-34 não ativa editor | Criar porta fixa (“Copilot deste ponto”) no card importado? |
| Rodapé/ruído no parser S-34 | “N.° 3-T 9/15 A SER ABRANGIDO…” vira subponto | Filtrar rodapé/licença no extrator (Fase 2). |
| Espaços internos em palavras do S-34 | “E m”, “J eová”, “C rescimento” | Corrigir junção de runs do DOCX (Fase 2). |
| Backup / chave DeepSeek | `allowBackup` + DataStore em texto claro | `allowBackup=false`/`dataExtractionRules` excluindo DB/DataStore; Keystore para a chave. |
| Keystore de release | Release assina com a debug keystore | Gerar keystore de release fora do repositório antes de distribuir. |
| Sync de seções/subpontos | P0 de 2026-10-19; correções posteriores | Revisar identidade/reconciliação multi-device antes de prometer sync. |

## Regra de processo

Antes de remover qualquer item “congelado” ou mexer em decisão pendente:

1. Registrar a decisão (data + motivo) neste arquivo ou no relatório da sessão.
2. Garantir teste JVM que trave o comportamento escolhido.
3. Validar no aparelho de referência (Redmi 9 / A34 / tablet) quando a mudança for de UX.
