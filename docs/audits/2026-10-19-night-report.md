# Relatório noturno — 2026-10-19

## Resumo executivo

- **6 auditorias concluídas** (A1 crash, A2 data integrity, A3 leaks, A4 main thread, A5 security/privacy, A6 acessibilidade), todas read-only.
- **Nível 1 do verifier** já estava implementado em `58c4837`; nesta rodada fechei a política de chat testável e o caso REF em warn-only (`c20c830`).
- **4 candidatos a P0**, ~20 P1 e ~25 P2. **Nenhum P0 foi corrigido** — todos são decisão de produto/arquitetura e estão no relatório de auditoria.
- Suíte final: **2095 testes, 0 falhas**.
- Árvore limpa; nenhum WIP esquecido.

## Top 5 descobertas críticas

1. **P0 — Sync de seções/sub-pontos pode perder e ressuscitar dados.**  
   `speech_sections`/`sub_points` têm `UNIQUE(noteId, order)` e `@Insert(REPLACE)`; o pull é por `id` e não reconcilia `order`; o push usa um snapshot lido antes do pull. Dois aparelhos criando na mesma posição podem alternar a seção a cada sync. Evidência: `AppDatabase.kt:280-290,325-335,427-430,458-461`; `SyncWorker.kt:488-592`.

2. **P0 — Importação de esboço e Biblioteca roda na Main.**  
   `OutlineRepository.previewFile` faz cópia SAF + PDFBox/ZIP/SQLite/AES + parser + `RefDetector` na Main; `LibraryRepository.importUri` copia até 400 MB na Main, duas vezes, e deixa `.tmp` no cache. Gatilho comum → ANR. Evidência: `CopilotViewModel.kt:4295-4393`; `OutlineRepository.kt:28-90`; `LibraryViewModel.kt:56-65`; `LibraryRepository.kt:94-166`.

3. **P0 — Qualquer tela mata o TTS compartilhado.**  
   O `VoiceRouter` é injetado em Stage/Tribuna/Copilot; cada ViewModel chama `shutdown()` no `onCleared`. Sair do Ensaio/chat mata o TTS do processo inteiro; o Piper pode ficar `ready=true` com o scope cancelado. Evidência: `MainActivity.kt:72-80`; `StageViewModel.kt:195-197`; `TribunaViewModel.kt:183-184`; `CopilotViewModel.kt:4883`; `VoiceRouter.kt:57-60`.

4. **P1 — Autosave pode perder a última edição.**  
   O debounce de 400 ms vive no `viewModelScope`/`innerScope` e não há flush no Back/`onCleared`; sair em <400 ms descarta o job. Evidência: `EditorViewModel.kt:217-226`; `SectionsController.kt:437-451,512-519`.

5. **P1 — API key DeepSeek em texto claro + backup habilitado.**  
   `deepseek_api_key` está no DataStore comum; `allowBackup=true` sem `fullBackupContent`/`dataExtractionRules` inclui DB, chat e DataStore no backup. Evidência: `SettingsStore.kt:15,88-90`; `AndroidManifest.xml:6`.

## Verifier — medição real no tablet

Prompt longo em Profundo, com 13 citações sem fonte no corpus:

```
verifier chat: flags=quote=13 flaggedChars=2303 keptChars=3820 strictChars=1524
```

| Métrica | Valor |
|---|---|
| Trechos sinalizados | 13 citações |
| Chars sinalizados | 2.303 |
| Texto mantido (warn-only) | 3.820 chars |
| Texto que o strict entregaria | 1.524 chars |
| Amputação evitada | **~60%** |

A tela final mostrou o texto inteiro e **um** aviso:

> ⚠️ Revise: 13 trechos sinalizados: 13 citações; há citação(ões)/versículo(s) fora dos trechos. O texto foi mantido.

Print: `/tmp/opencode/l1-result.png`.

### Sobre o “REF cirúrgico”

No chat, `warnOnly=true` preserva **a frase inteira** — não há remoção de REF para “cirurgizar”. O teste `warnOnlyMantemFraseComReferenciaIntacta` garante esse comportamento. Em conteúdo estrito (oratória/apply), o comportamento atual continua removendo a frase; uma cirurgia de REF ali é uma decisão de produto para o Nível 3, não aplicada nesta rodada.

## O que foi commitado

| Commit | Conteúdo |
|---|---|
| `58c4837` | warn-only no chat, métricas por categoria, unidades técnicas e aviso único (já estava no `main`) |
| `c20c830` | política `verifyChatText` testável + teste de REF em warn-only + 2 testes de política |
| `d796446` | `docs/audits/2026-10-19-full-audit.md` |
| *(este relatório)* | `docs/audits/2026-10-19-night-report.md` |

## O que ficou pendente (decisão do usuário)

1. **P0 sync:** corrigir identidade de seção/sub-ponto (não usar `order` como unicidade; `@Upsert`; reconciliação). Exige desenho e teste com dois devices.
2. **P0 imports:** mover `OutlineRepository`/`LibraryRepository` para IO/Default e limpar `.tmp` no `finally`.
3. **P0 TTS:** ownership no topo (Activity/Application) e VMs só `stop()`; shutdown idempotente.
4. **P1 autosave:** flush em `onBack/onCleared` com `NonCancellable`.
5. **P1 delete:** transação em `deleteForever`/`Library.delete` + limpar chat da nota.
6. **P1 sync fields:** `folderId=null`, `discourseType`, `tribunaJson`.
7. **P1 privacy:** excluir DataStore/DB do backup e cifrar a API key.
8. **A11y quick wins:** swatches 48dp, controles do Ensaio, linhas compactas, label do composer.
9. **Nível 2 do verifier:** cobertura de versículos do S-34/dossiê no corpus — o maior ganho de qualidade.
10. **Language Guide:** ainda não iniciado.

## Estado do build

- **Testes:** 2095/0.
- **Verifier:** 28 testes; **avisos/política:** 11.
- **APK debug:** compilado e instalado no tablet durante a validação.
- **Release v2.2:** publicado no GitHub (tag `v2.2`); este código é **posterior** ao release e não foi publicado em APK.
- **Árvore:** limpa; `local.properties` ignorado.
- **`origin/main`:** será atualizado com `c20c830`, `d796446` e o commit deste relatório.

## Recomendação para amanhã

1. **P0 sync** (perda de dados) — maior prioridade.
2. **P0 imports fora da Main** — ANR em fluxo comum.
3. **P0 TTS ownership** — recurso central.
4. **P1 autosave + delete transacional** — perda de dados menor, correção barata.
5. **Privacidade/backup** — antes de distribuir.
6. **A11y quick wins** — antes de distribuir.
7. **Nível 2 do verifier + Language Guide** — qualidade de conteúdo.

## Observações de método

- Auditorias **estáticas**, sem build/profiler/TalkBack/backup real/dois devices. Severidades de ANR/jank são qualitativas.
- Os P0/P1 marcados ✅ no relatório de auditoria foram reconferidos no código; os demais vêm da leitura dos arquivos citados.
- Nenhum segredo foi impresso. `google-services.json` está ignorado e não tracked.
