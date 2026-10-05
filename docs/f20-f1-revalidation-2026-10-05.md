# Re-Validação F20-F1 — A34 (2026-10-05, sessão 2)

## Ambiente
- **Device:** SM-A346M (ADB wireless) — caiu no meio (ver Incidente)
- **APK:** `536f9f0` — `assembleDebug` ✓ + `adb install -r` Success
- **Chave DeepSeek:** presente e válida (`sk-`, 35 chars)
- **Modelo Gemma:** presente (2.588.147.712 bytes)
- **Provider inicial:** `deepseek` (explícito) → depois trocado para `auto`

## Bloco A — Bug #8 (S-34 persiste): FALHOU — Bug #9
- Import de `s34-35.docx` via chat: **draft criado** (`kind=draft`), mas
  **nenhum attachment novo** e **nenhum log do `S34ImportHook`**.
- Tabelas após o import: `s34_outlines=0`, `s34_sections=0`,
  `s34_subsections=0`, `s34_references=0`.
- **Causa (Bug #9):** `S34Detector.isS34` exige o **literal “S-34” no texto**
  (`MARKER_RE = \bS[-\s]?34\b`); o arquivo real começa com “N.º 35 …” e **não
  contém o literal** → o hook nunca roda, mesmo com o fix `ea7b2e3`.
  O teste JVM passou porque a fixture sintética contém “S-34”.
- **Correção sugerida (não aplicada):** no caminho de import, usar o **nome do
  arquivo** como sinal (ex.: `S34Detector.isS34(raw, fileName)` aceitando
  `s34*`/`S-34*` no nome), ou relaxar o portão do marcador quando o filename
  indica S-34. Registrar como P0 (bloqueia o fluxo S-34 estruturado).

## Bloco B — Bug #6 (sonda): PASSOU
- “Testar conexão”: `Testando…` → **Online** (~3–5 s).
- Log confirma o novo tratamento: `deepseek vazio por length; retry com
  budget=128` (a sonda com 64 tokens sofreu o esvaziamento e o retry resolveu).

## Bloco C — Bug #7 (cards com provider ativo): PARCIAL
- Com DeepSeek ativo, “Quais referências o esboço cita?” postou **mensagem
  `kind=refs`** (intercept funcionou).
- **Card `⚠️ citação sem fonte no acervo` apareceu** (Despertai! 8/2013).
- **Card `📖 No acervo` não observado** nesta rodada: nenhuma das refs do
  esboço teve match positivo de conteúdo (uma resolvida sem aviso/snippet
  por edição não indexada; outra com aviso). Mecanismo exercitado; falta um
  caso positivo no corpus.

## Bloco D — Fallback (auto): PARCIAL
- Caso 1 (DeepSeek disponível): **auto → resposta remota** (origin COPILOT,
  sem fase local) ✓.
- Caso 3 (offline): `cmd connectivity airplane-mode enable` **não derrubou o
  Wi-Fi** (rede ativa) — o teste offline real exigiu `svc wifi disable`;
  **ADB caiu logo depois** e o caso não foi concluído.
- Casos 2 (sem chave) e 4 (restaurado): não executados.

## Blocos E (proposta) e F (estabilidade)
- **Não executados** (queda do ADB).

## Incidente — ADB caiu; aparelho ficou em modo avião
- Durante o caso offline, o ADB wireless caiu (`no devices`).
- **Ação do teste deixou o aparelho em modo avião com Wi-Fi desligado**
  (`airplane_mode_on=1`, `svc wifi disable`). **O dono precisa reativar**
  manualmente (modo avião off + Wi-Fi on) quando retomar.

## Bugs
- **Bug #9 (novo, P0):** detector S-34 exige literal no texto; arquivo real
  sem o literal não persiste `s34_*` (detalhado no Bloco A).
- Bugs #6 e #7: correções confirmadas no device (B passou; C com evidência
  positiva no aviso e mecanismo ativo).

## State
- Nenhum código alterado durante a validação.
- Re-validação **parcial**: A falhou (Bug #9), B passou, C/D parciais, E/F
  não executados; ADB caiu.
