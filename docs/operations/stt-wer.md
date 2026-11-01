# WER humano — STT local (sherpa-onnx NeMo CTC pt-BR)

Mede a qualidade do STT embarcado com fala humana real. É a única medição de
qualidade que falta; funcionamento (pipeline, offline, stop, cancel, fallback,
45 min) já foi validado no A34.

## 1. Gravar as amostras (3 × 30–60 s)

| Amostra | Condição |
|---|---|
| `A1` | fala clara e calma |
| `A2` | fala rápida (natural, sem exagero) |
| `A3` | fala baixa ou com ruído leve |

Grave com o gravador do aparelho (ou outro), falando um texto que você possa
transcrever depois. Termos JW são bem-vindos (ex.: "Jeová", "TNM", "A Sentinela").

## 2. Converter para WAV 16 kHz mono 16-bit

```bash
ffmpeg -i amostra.m4a -ar 16000 -ac 1 -sample_fmt s16 -c:a pcm_s16le A1.wav
```

## 3. Ground truth

Crie `ground_truth.txt` com **uma linha por amostra**, no formato `nome|texto`:

```
A1|Boa tarde irmãos, hoje vamos falar sobre a esperança e como a fé nos ajuda.
A2|Quando enfrentamos provações lembramos que Jeová é nosso pastor e nos guia.
A3|A esperança é como uma âncora segura e firme para a alma.
```

O texto deve ser **exatamente** o que foi falado (pontuação é ignorada na
normalização; acentos e palavras contam).

## 4. Rodar

Requisitos: A34/tablet conectado por ADB, app **debug** com o modelo baixado
(Configurações → Voz → Baixar), e o modo Sherpa selecionado não importa aqui
(o teste usa o modelo direto).

```bash
scripts/stt-wer.sh /caminho/da/pasta
```

O script envia os WAVs para
`/sdcard/Android/data/com.bettertalker.app.debug/files/stt-wer/`, roda o teste
instrumentado `SttWerTest` e imprime:

```
LOAD|load_ms=…
RESULT|A1|wer=0.082|rtf=0.052|elapsed_ms=2010|dur=38.70|text=…
SUMMARY|samples=3|avg_wer=0.091|max_wer=0.150|avg_rtf=0.053
```

## 5. Interpretar

| WER (fala clara) | Decisão |
|---|---|
| ≤ 10% | excelente |
| 10–20% | bom para uso pessoal → promover Sherpa a "validado" (ainda opt-in) em 3.1.0/41 |
| 20–35% | manter experimental; melhorar VAD/hotwords; testar Whisper tiny |
| > 35% | manter o sistema como default; Sherpa vira spike |

Independente do resultado, o **default continua Sistema** até decisão explícita.

## Comparação com o STT do sistema

O teste instrumentado cobre só o Sherpa (arquivo). Para comparar com o sistema,
use o caminho qualitativo: abra o Treino com análise, fale a mesma amostra,
copie a transcrição do resultado e rode o mesmo WER à mão (ou adapte
`SttWerTest.wer()` em um teste JVM).

## Alternativa sem WAV

Abra o Treino com análise, fale 30–60 s, copie a transcrição do resultado e
compare com o que você disse. Não é WER formal, mas responde "dá para usar?".
