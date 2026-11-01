# Validação STT embarcado — A34 (SM-A346M) — 2026-11-01

**Build:** debug `com.bettertalker.app.debug` (main @ e79fc5e)
**Motor:** sherpa-onnx NeMo CTC pt-BR int8 (CC-BY-NC-4.0)
**Modelo:** 131.279.704 B (125 MB) + tokens.txt (795 B) em `filesDir/stt/`

## Cenários

| # | Cenário | Resultado | Evidência |
|---|---------|-----------|-----------|
| 1 | Baixar modelo 125 MB; conferir SHA-256 | ✅ | Download 100% → extração `model.int8.onnx` 131 MB + `tokens.txt`; SHA `bf340af2…` confere (sem erro). Repetido 2× |
| 2 | Selecionar Sherpa; reiniciar; persistência | ✅ | DataStore `stt_engine=sherpa`; após force-stop a UI voltou com Sherpa marcado e licença CC-BY-NC visível |
| 3 | Fala humana real (30–60 s) | ⏳ pendente | Sem fonte de fala humana no ambiente; áudio ambiente gerou 20–33 palavras (ruído), pipeline decodificou |
| 4 | Pause/resume no meio | ✅ | `⏸ Pausado` → `AudioCapture: Capture stopped`; `Retomar` → `● Ouvindo… 00:05` + `Capture started`; transcrição preservada |
| 5 | Stop no meio da frase; última entra | ✅ | Log: `Segment decoded: 86 chars` → `Stopped: transcript=86 chars` (ordem correta; fix do timeout 15 s) |
| 6 | Cancel; nada vaza | ⏳ pendente | Caminho `release()` executa `cancel()` + generation++; não exercitado explicitamente na UI |
| 7 | Modo avião após download | ⏳ pendente | — |
| 8 | 45 min; memória/bateria/tela | ⏳ pendente | Sessão curta medida; `keepScreenOn` já no código (StageScreen:175) |
| 9 | Remover modelo com Sherpa ativo → fallback | ✅ | `rm -rf files/stt` com `stt_engine=sherpa`: treino iniciou sem logs `SherpaStt` (STT do sistema), `● Ouvindo…` |
| 10 | Sistema × Sherpa no mesmo áudio | ⏳ parcial | Ambos transcreveram áudio ambiente; comparação WER exige fala humana |

## Medições

| Métrica | Valor |
|---|---|
| Carga do modelo (cache quente) | ~60 ms (log 14:07:10.570 → .628) |
| Pico de memória nativa (captura, sem decode) | ~256 MB |
| Memória nativa após `release()` | ~124 MB (delta ~132 MB) |
| ANR | nenhum (modelo carrega em thread `sherpa-decode`) |
| Buffer AudioRecord | 1280 B @ 16 kHz mono |

## Observações

- O banner do Ensaio agora diferencia: "transcrição 100% local (Sherpa)" quando o motor
  local está ativo; senão mantém o aviso de rede do sistema.
- Com áudio ambiente (sem fala), o resultado mostra honestamente "Pouca fala capturada"
  — o gate de confiança funciona com o motor local.
- O fix de timeout (`ANALYSIS_STOP_TIMEOUT_MS` 4,5 s → 15 s) foi confirmado: a última
  frase é decodificada antes de `finalizeStopped`/métricas.

## Pendências para fechar o gate

1. 2–3 gravações humanas de 30–60 s (fala natural + fala baixa) → WER/RTF.
2. Cenário cancel explícito (descartar resultado sem vazar texto).
3. Modo avião (offline real).
4. 45 min contínuos: memória, bateria, temperatura, tela.
5. Comparação WER sistema × Sherpa no mesmo áudio.
