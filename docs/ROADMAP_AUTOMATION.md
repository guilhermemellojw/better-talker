# Better Talker — Automação de acompanhamento do roadmap

O workflow [Roadmap status (proposal)](../.github/workflows/roadmap-status.yml) consulta a API do GitHub e propõe, em um **PR de documentação**, uma nova versão de [`ROADMAP_STATUS.md`](ROADMAP_STATUS.md).

## O que é atualizado

- SHA e checks conhecidos da `main`.
- PRs abertos e respectivos HEADs/checks.
- PRs recentemente integrados, agrupados por frente (*Mentor*, *Referências e UX*, *TRUST*, *Beta e infraestrutura*, *Documentação* ou *Outros*).
- Avisos de evidência ausente, gates de revisão e limitações.

O sistema **não** transforma contagem de PRs em percentual de beta, não marca fases como aceitas sem teste e não edita `docs/ROADMAP.md` automaticamente. O roadmap estratégico permanece sob controle do mantenedor, inclusive enquanto o PR #10 estiver aberto. As labels opcionais `roadmap:mentor`, `roadmap:ux`, `roadmap:trust`, `roadmap:beta`, `roadmap:docs` prevalecem sobre classificação heurística por título/branch.

## Quando roda

- `push` na `main` (integrações).
- Abertura, atualização, encerramento e reabertura de PR (`pull_request_target`).
- Conclusão do workflow `Build Android APK` (para registrar a transição de CI pendente para verde/vermelho).
- Agendamento diário e execução manual (`workflow_dispatch`).

## Segurança e governança

1. O workflow executa **somente o código da main**, nunca o código de uma contribuição recebida. O gerador é somente leitura e usa o `GITHUB_TOKEN` para consultar API.
2. As mudanças são propostas na branch exclusiva `automation/roadmap-status`, em PR para `main`. Não há merge automático, tag, release, version bump ou alteração de código Android.
3. A execução usa `concurrency` para evitar duas atualizações simultâneas; não há force push. Caso encontre mudanças fora de `docs/ROADMAP_STATUS.md`, falha sem publicar.
4. A integração do PR de status depende da revisão do mantenedor. Após o merge, o workflow pode criar uma nova proposta quando houver atualização factual.
5. **Configuração necessária:** em *Settings → Actions → General → Workflow permissions*, permitir criação de pull requests pelo GitHub Actions, além das permissões necessárias de conteúdo/PR. Se a política do repositório impedir essa opção, o workflow falhará ao publicar a proposta; nesse caso, revisar logs e conceder apenas as permissões exigidas. Não inserir PAT nem segredos adicionais.
6. O relatório usa uma janela de até 300 PRs atualizados. Estados/checks não disponíveis são marcados como **Não verificado**, não como sucesso.

## Idempotência e propagação de erro

A comparação `--compare` distingue三个 estados: `CHANGE` (exit 0), `NO_CHANGE` (exit 1)
e `ERROR` (exit 2). Falha de leitura/comparação deixa o workflow **vermelho** —
nunca é interpretada como "sem mudança".

A idempotência ignora apenas proveniência da consulta (SHA da `main` e estado dos
checks da `main`) ao decidir se vale a pena propor uma revisão. O painel gerado
continua registrando o SHA e o CI reais; mudança legítima no estado de check de
um PR é conteúdo material e gera proposta. Nenhum timestamp é introduzido e
nenhuma informação material é removida para evitar churn.

## Validação

```bash
python3 -m unittest discover -s tests -p 'test_roadmap_status.py' -v
python3 scripts/roadmap_status.py --repo guilhermemellojw/better-talker --output /tmp/ROADMAP_STATUS.md
```

O segundo comando exige acesso à API do GitHub e usa `GITHUB_TOKEN` quando disponível. O arquivo gerado é determinístico para o mesmo conjunto de fatos; evita atualizar o PR apenas por mudança de horário.

## Integração com o planejamento estratégico

Quando o PR #10 for revisado, incluir um link de `docs/ROADMAP.md` para `docs/ROADMAP_STATUS.md`. **Não incorporar automaticamente a classificação heurística como decisão estratégica**, nem confundir PR integrado com beta pronto.
