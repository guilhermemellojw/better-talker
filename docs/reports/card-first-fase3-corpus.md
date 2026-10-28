# Card-first fase 3 — métricas e corpus (2026-10-28)

- Branch: `exp/card-metrics` (base `dcb8f3f`, inclui atomicidade).
- Commit: `46118a2`.
- Suíte: **2.611 testes / 0 falhas**. `lintRelease`/CI: verdes.
- Escopo: **só telemetria**; nenhuma mudança de UX, prompt ou fluxo.

## Eventos implementados

`CardMetrics` (logcat, tag `CardMetrics`), somente metadados:

- `card|emitted|route=...|target=...|source=...|chars=...|cards=...`
- `card|applied|action=APPEND|stale=...|undo=true|chars=...`
- `card|replaced|confirmed=true|stale=...|chars=...`
- `card|undone|restored=true`
- `card|stale|action=DETECTED|APPEND|chars=...`
- `card|dismissed|reason=USER|GATE_NO`
- `card|gate|action=ACCEPT|REJECT|IGNORED|reason=...`
- `card|target|kind=NO_TARGET|picked=USER_PICK|target=SECTION`
- `card|target|kind=TARGET_MISSING|picked=NONE`
- `card|failed|stage=INSERT|UNDO`
- `card|second_ignored|count=...`
- `card|transform|target=...|source=TRANSFORM|chars=...`
- `card|superseded|target=...`
- `proposal_text|used|route=ORATORY`

As linhas são puras/testáveis (`CardMetricsTest`); nenhum texto de
usuário/card entra no log.

## Corpus planejado (A34)

20 turnos no tópico `FOMOS CRIADOS...`, cobrindo:

1. sugestão para o ponto;
2. reescrever mais curto;
3. mais natural;
4. outra versão;
5. “e o ponto 2?”;
6. pergunta de referências;
7. gerar texto para o ponto;
8. introdução curta;
9. explicação (não deve virar card);
10. opinião sobre o esboço (não deve virar card);
11. transição;
12. conclusão;
13. versão mais direta;
14. resumo em uma frase;
15. pontos principais;
16. aplicação prática;
17. ilustração;
18. parágrafo de abertura;
19. versão mais calorosa;
20. pergunta conceitual (“eternidade no coração?”).

O runner está em `/tmp/opencode/run_card_corpus.sh` e o banco limpo em
`/tmp/opencode/metrics-corpus.db`. Ele instala o APK, abre o tópico,
envia os 20 turnos com ADBKeyboard e coleta `CardMetrics`.

## Bloqueio atual

O A34 caiu da rede antes da coleta: `192.168.3.5` sem rota, sem mDNS e
sem dispositivo ADB. O tablet (`192.168.3.15`) está ocupado e é gate da
tag, não da fase 3. **Nenhum número de corpus foi inventado**; a branch
fica sem merge até rodar os 20 turnos.

## Critérios de saída (a medir)

- cobertura de card nos turnos com texto aplicável;
- aplicados > propostas no chat;
- zero insert no alvo errado;
- gate sem falso positivo;
- sem aumento de citação sem apoio;
- oratória inalterada;
- suíte + lintRelease verdes.
