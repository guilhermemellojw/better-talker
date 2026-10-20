# Pacote de Pendências — Branch `tests/pendencias-2026-10-19`

## Resumo executivo

- **Branch:** `tests/pendencias-2026-10-19`
- **Base:** `ad15d07` (o `main` já continha os dois commits do fix de paste antes desta tarefa; não houve push para `main` agora)
- **Fases concluídas em código/testes:** 4/4 (Fase 1 TTS, Fase 2 Verifier, Fase 3 Language Guide, Fase 4 RC v2.3)
- **Commits na branch:** 5 + este relatório
- **Testes JVM:** **2125/0**
- **APK release:** `android/app/build/outputs/apk/release/app-release.apk` (98 MB, debug-signed para sideload)
- **SHA-256 do APK:** `970c9ecb2c936310be30d39c128a02b015a43852fdf71119dbf4018c97eb6d16`
- **`main`:** permaneceu em `ad15d07`; nenhum merge, nenhum push, nenhuma tag.
- **Status para revisão:** ⚠️ **pronto para revisão de código; validação em device pendente** (A34 bloqueado por PIN).

---

## Fase 1 — TTS Ownership

- **Status:** ✅ código + JVM; ⚠️ validação audível no device bloqueada.
- **Commit:** `75c18d0` — `fix(tts): ownership por processo — sair da tela não mata o TTS`
- **Problema:** `StageViewModel`, `TribunaViewModel` e `CopilotViewModel` chamavam `shutdown()` no `onCleared()`. Como o `VoiceRouter` era compartilhado, sair de uma tela matava o TTS/Piper para o app inteiro.
- **Solução:** variante **singleton** (permitida no prompt):
  - novo `data/voice/TtsLifecycle.kt`: um `VoiceRouter` e um `PiperModelStore` por **processo**, criados com `applicationContext`;
  - `MainActivity` usa o singleton em vez de recriar o router por composição;
  - `DisposableEffect` faz `acquire()`/`release()`; o último `release()` chama apenas `stop()`, **nunca** `shutdown()`;
  - ViewModels agora chamam `stop()` no `onCleared()`;
  - `TribunaSpeaker.shutdown()` do wrapper virou `stop()` (compatibilidade);
  - `MainActivity.onDestroy()` só para a fala quando `isFinishing`.
- **Teste:** `TtsLifecycleTest` cobre acquire 2×, release 1×, release 1×, release extra (clampa em 0) e reacquire.
- **Validação no tablet:** bloqueada — o A34 está com PIN ativo e a tela não pôde ser desbloqueada. Não foi possível confirmar o áudio saindo de uma tela e voltando.

---

## Fase 2 — Verifier Nível 2: cobertura de fonte

- **Status:** ✅ código + JVM; ⚠️ métrica “13 → N” não medida no device.
- **Commit:** `36026ca` — `feat(verifier): resolve versículos do S-34 no corpus de verificação`
- **Commit de teste complementar:** `8b6ccf8` — citação resolvida não é sinalizada.
- **O que foi feito:**
  - `structuralBibleRefs(turnContext)`: extrai somente refs `S34RefType.BIBLE` do ponto atual e dos subpontos, sem duplicar.
  - `resolveBibleRefLines(refs, resolver)`: resolve cada ref no índice TNM local via `RoomReferenceResolver` e monta `[ref] texto`; refs não encontradas são omitidas (sem fallback fantasma).
  - `CopilotViewModel.resolveStructuralBibleLines(turnContext)`: usa `db.passageDao()`/`attachmentDao()`.
  - Em `answerRemote`, as linhas resolvidas entram no `verificationCorpus` antes do `GroundednessVerifier`; um log informa quantos versículos entraram.
  - O dossiê já resolve textos via `DossierBuilder` e injeta o bloco em `effectiveContextBlock`; esta fase acrescenta as refs estruturais do S-34 mesmo quando o dossiê não está disponível.
- **Testes:**
  - refs do S-34 só incluem Bíblia e deduplicam;
  - resolução injeta texto e omite ref não resolvida;
  - **caminho completo:** sem o versículo o gate remove a citação; com o texto resolvido a citação é preservada.
- **Antes/depois no device:** não medido. O A34 estava bloqueado por PIN. Além disso, o efeito depende de o índice TNM local conter o versículo; se não contiver, a linha é omitida e o comportamento antigo permanece — isso é intencional e está documentado.

---

## Fase 3 — Language Guide

- **Status:** ✅ código + JVM; ⚠️ validação de respostas no device bloqueada.
- **Commit:** `b6d3ef5` — `feat(language): guia de linguagem simples preservando termos TJ`
- **Novo arquivo:** `data/copilot/LanguageGuide.kt`
  - `TJ_TERMS_TO_PRESERVE`: termos que nunca devem ser traduzidos/trocados.
  - `ACADEMIC_TERMS_TO_AVOID`: academicismos a evitar (`outrossim`, `destarte`, etc.).
  - `LANGUAGE_PRINCIPLES`: frases curtas, voz ativa, segunda pessoa, exemplos do cotidiano, aplicação prática e preservação de termos TJ.
- **Personas atualizadas (5/5):**
  1. `CHAT_PERSONA` — guia completo;
  2. `GENERATION_VOICE_LINE` — referência compacta ao guia (o guia completo já vem de `CHAT_PERSONA` na oratória, evitando duplicar ~1 KB de prompt);
  3. `SPEECH_MENTOR` — guia completo;
  4. `DEMO_SCRIPTWRITER` — guia completo;
  5. `MINIMAL_SYSTEM_PROMPT` — guia completo.
- **Testes:** `LanguageGuideTest` cobre termos preservados, academicismos apenas na lista de “EVITE”, as 5 personas e os systems de chat/oratória.
- **Ajustes em testes existentes (intencionais):**
  - `OratoryProposalFlowTest`: o body JSON escapa as quebras de linha da persona; a asserção passou a comparar o prefixo estável + o guia, em vez da constante inteira.
  - `MinimalPasteTest`: o system mínimo deixou de ter “≤ 4 linhas” porque o guia entra de propósito; o teste agora garante que não voltaram TAIL, regras do S-34 ou contexto de dossiê.
- **Validação no tablet:** bloqueada pelo PIN.

---

## Fase 4 — RC v2.3 (preparado, NÃO publicado)

- **Commit:** `aa07e30` — `release: v2.3 (versionCode 6) — TTS + Verifier + Language Guide`
- **versionCode:** `6`
- **versionName:** `"2.3"`
- **README:** seção “Novidades da v2.3” + status de RC interno.
- **APK:** `android/app/build/outputs/apk/release/app-release.apk`
  - `assembleRelease`: **BUILD SUCCESSFUL** (2m17s)
  - Assinado com a keystore de debug (sideload), conforme `android/app/build.gradle`.
  - Instalado no A34 para smoke test; `dumpsys package` confirmou `versionCode=6`, `versionName=2.3`.
  - **APK não foi commitado.**
- **Não feito (por segurança):**
  - ❌ `git tag v2.3`
  - ❌ push/merge para `main`
  - ❌ force push, rebase de commits publicados, reset em `origin/main`

---

## Fase 5 — Relatório + preparação para merge

- Este arquivo: `docs/reports/2026-10-19-pendencias-branch.md`
- Nenhum push para `main`, nenhuma tag e nenhum merge foram executados.

---

## Bloqueios encontrados

1. **A34 bloqueado por PIN.** O APK 2.3 foi instalado com sucesso, mas a tela permaneceu no keyguard (`Inserir PIN`). Não tentei adivinhar o PIN. Consequência: as validações de TTS, do verifier e das respostas do Language Guide não puderam ser feitas no device nesta sessão.
2. **Métrica do verifier depende do índice TNM.** Mesmo com o device desbloqueado, a redução de 13 citações sinalizadas só acontece se o texto do versículo estiver indexado. O teste JVM cobre o caminho “texto resolvido → corpus → citação preservada”; a contagem real precisa ser medida com o acervo bíblico indexado.
3. **Estado do `main` diferente do prompt.** O prompt assumia `origin/main = b0ce722` e o fix de paste como WIP. Na realidade, o `main` já estava em `ad15d07`, com os commits `7ea6fcd` e `ad15d07` publicados antes desta tarefa. A branch foi criada a partir desse estado; **nenhum push para `main` foi feito agora**.

---

## Como revisar

```bash
# 1. Ver os commits da branch
git log main..tests/pendencias-2026-10-19 --oneline

# 2. Ver o diff completo
git diff main..tests/pendencias-2026-10-19

# 3. Rodar os testes localmente
cd android
taskset -c 4-7 ./gradlew --no-daemon --max-workers=2 :app:testDebugUnitTest

# 4. Instalar o APK de teste (após desbloquear o tablet)
adb install -r android/app/build/outputs/apk/release/app-release.apk

# 5. Se aprovado, merge manual para main
git checkout main
git merge --no-ff tests/pendencias-2026-10-19
git push origin main

# 6. Criar a tag somente após o merge aprovado
git tag -a v2.3 -m "RC v2.3"
git push origin v2.3

# 7. Se rejeitado, deletar a branch
git branch -D tests/pendencias-2026-10-19
git push origin --delete tests/pendencias-2026-10-19
```

---

## Recomendação para amanhã

1. Desbloquear o A34 e repetir a validação de TTS: Ensaio → Ouvir → Home → outra nota/Ensaio → Ouvir. O esperado é o botão continuar funcional e o áudio sair na segunda tela.
2. Verificar se o índice TNM local tem os versículos do S-34 usado no teste. Se não tiver, indexar antes de medir a redução de citações; sem isso, o Nível 2 não tem texto para injetar.
3. Revisar o diff e, se aprovado, fazer o merge manual + tag. A branch está pushada como backup; nada em `main` foi alterado nesta tarefa.
4. Se quiser a validação literal do prompt (guia completo também em `GENERATION_VOICE_LINE`), é uma mudança de uma linha; hoje a linha referencia o guia que já está em `CHAT_PERSONA`, para não duplicar tokens no prompt de oratória.
