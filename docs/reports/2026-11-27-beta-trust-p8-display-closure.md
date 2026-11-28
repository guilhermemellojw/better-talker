# BETA-TRUST/P8 — fechamento do display de Salmos

**Baseline:** PR #86 @ `c693c28e2cdc824d5114a809f072371b65fc691e` (parecer D/P7 `7238022901`).
**Origem do P2:** alias `salpo → sal` introduzido no P5 para acomodar fixture com grafia inválida; isso permitia que “Salpo 103:20” herdasse autoridade de Salmos e permanecesse visível.

## Correção
- Alias removido de `RefCanonicalizer.BOOK_NORMALIZE`; comentário documentando que grafias inválidas não ampliam autoridade documental.
- Fixtures de teste com “Salpo” normalizadas para “Salmos”/“Salmo” na origem (`GenerationVerificationTest`, `OruaryDocumentarySourcesTest`).
- Display canônico permanece via `RefDetector.BIBLE_BOOKS`: “Salmo 103:20” → “Salmos 103:20”; nenhuma política TNM/jw.org alterada.

## Fail-first
`BetaTrustP8DisplayTest.aliasSalpoNaoDeveSerAceitoComoSalmos` falhou antes da correção (grafia inválida era aceita) e passou depois.

Regressões explícitas: alias válido rejeitado, display correto de Salmos, versículo incorreto rejeitado, citação literal sem apoio rejeitada, referência válida preservada.

## Verificação
- Focados P8: **5/5**.
- 30 focados anteriores: **30/30**.
- 10 probes canônicas e 8 gap probes P6 preservadas na suíte.
- Suíte JVM integral, execução única, sem builds concorrentes: **4697 testes, 0 falhas, 0 erros, 0 skipped** (470 suítes).

## Limitações
O arquivo-fonte do guard P7 não está versionado no repositório; a cobertura equivalente foi executada pelas probes canônicas, gap probes, focados e suíte integral. Homologação física (Room/ViewModel real, CAS concorrente, UI, ADB) permanece fora do escopo. Não autocertificando; aguardando D.
