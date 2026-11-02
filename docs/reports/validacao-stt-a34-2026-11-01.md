# Validação STT embarcado — A34 (SM-A346M) — 2026-11-01

**Build:** debug `com.bettertalker.app.debug` (main @ e79fc5e)
**Motor:** sherpa-onnx NeMo CTC pt-BR int8 (CC-BY-NC-4.0)
**Modelo:** 131.279.704 B (125 MB) + tokens.txt (795 B) em `filesDir/stt/`

## Cenários

| # | Cenário | Resultado | Evidência |
|---|---------|-----------|-----------|
| 1 | Baixar modelo 125 MB; conferir SHA-256 | ✅ | Download 100% → extração `model.int8.onnx` 131 MB + `tokens.txt`; SHA `bf340af2…` confere (sem erro). Repetido 2× |
| 2 | Selecionar Sherpa; reiniciar; persistência | ✅ | DataStore `stt_engine=sherpa`; após force-stop a UI voltou com Sherpa marcado e licença CC-BY-NC visível |
| 3 | Fala humana real (30–60 s) | ✅ quantitativo | 2 gravações de 43 s lendo texto fixo (ritmo ~119 wpm). WER MODEL 0,0% / PIPELINE 0,5% (A2 0,0%; A3 1,1%). RTF pipeline 0,073 |
| 4 | Pause/resume no meio | ✅ | `⏸ Pausado` → `AudioCapture: Capture stopped`; `Retomar` → `● Ouvindo… 00:05` + `Capture started`; transcrição preservada |
| 5 | Stop no meio da frase; última entra | ✅ | Log: `Segment decoded: 86 chars` → `Stopped: transcript=86 chars` (ordem correta; fix do timeout 15 s) |
| 6 | Cancel; nada vaza | ✅ | Sessão com decode (43 chars) → sair do treino: `Capture stopped` → `Cancelled` → `Released`; nenhum decode após o cancelamento; nenhum resultado/métrica exibido |
| 7 | Offline (Wi-Fi desligado; modo avião já ativo) | ✅ | Wi-Fi desligado às 15:30:56; `Segment decoded: 186 chars` às 15:32:06 com Wi-Fi ainda desligado (verificado às 15:32:13). Transcrição sem rede |
| 8 | 45 min; memória/bateria/tela | ✅ | 45 min (15:34→16:20) com análise ativa e áudio em loop, USB (carregando). Processo vivo o tempo todo (pid 21443), sem kill/ANR. Native heap 566→590 MB (platô), PSS total 839–874 MB (estável). Temperatura 30,7–31,0 °C (sem throttling). Bateria 22%→25% (ganho líquido com USB) |
| 9 | Remover modelo com Sherpa ativo → fallback | ✅ | `rm -rf files/stt` com `stt_engine=sherpa`: treino iniciou sem logs `SherpaStt` (STT do sistema), `● Ouvindo…` |
| 10 | Sistema × Sherpa no mesmo áudio | ⏳ pendente | Sherpa medido (0,5% pipeline); falta o mesmo áudio no motor do sistema |

## Medições

| Métrica | Valor |
|---|---|
| Carga do modelo (cache quente) | ~60 ms (log 14:07:10.570 → .628) |
| Pico de memória nativa (captura, sem decode) | ~256 MB |
| Memória nativa após `release()` | ~124 MB (delta ~132 MB) |
| ANR | nenhum (modelo carrega em thread `sherpa-decode`) |
| Buffer AudioRecord | 1280 B @ 16 kHz mono |
| 45 min — Native heap | 566 → 590 MB (platô) |
| 45 min — PSS total | 839–874 MB (estável) |
| 45 min — temperatura | 30,7–31,0 °C |
| 45 min — processo | vivo (pid 21443), sem kill/ANR |

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

## WER humano (harness `SttWerTest`, 2026-11-02)

| Amostra | Duração | Ritmo | MODEL_WER | PIPELINE_WER | RTF (pipeline) |
|---|---|---|---|---|---|
| A2 (lida, natural) | 42,9 s | ~119 wpm | 0,0% | 0,0% | 0,078 |
| A3 (voz baixa) | 43,2 s | ~118 wpm | 0,0% | 1,1% | 0,069 |
| A4 (rápida) | 27,9 s | ~183 wpm | 7,7% | 7,7% | 0,079 |
| **Média** | — | — | **2,6%** | **2,9%** | **0,075** |

- Texto fixo de ~85 palavras lido pelo usuário (ground truth conhecido).
- `PIPELINE` = caminho do app (chunks → VadSegmenter → decode por segmento).
- Memória no run: PSS 597 MB no pico, 188 MB após release.
- Amostra A1 "clara" não foi gravada, mas A2 cobre fala natural/clara a ~119 wpm.
- A4 (183 wpm) mostra o custo da fala rápida: 7,7% (ainda dentro do aceitável),
  muito abaixo dos 67% do spike sintético a 1,25×.

**Decisão:** `pipeline_avg_wer` 2,9% ≤ 10% → **excelente**. Sherpa promovido de
"experimental" para "validado (opt-in)" no 3.1.0/41, mantendo o sistema como
default.
