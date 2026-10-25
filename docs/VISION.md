# Better Talker — Visão v2.0

**Atualizado:** 2026-10-25 · **Build de referência:** v2.7.4 (versionCode 24)

## O que É

Ferramenta Android **nativa e local-first** para preparar discursos bíblicos e
partes da reunião “Nossa Vida e Ministério Cristão” (V&M) no contexto das
Testemunhas de Jeová. É um **mentor de preparação**: organiza o esboço, ajuda a
desenvolver o raciocínio, aponta lacunas e ensaia a fala — mas **quem decide e
escreve é o usuário**.

Princípios:

- **Editor primeiro:** o texto vive em seções com tempo (INTRO / BODY / CONCLUSÃO).
- **Humano no controle:** sugestões do Copilot são propostas; nada é aplicado sem aceite.
- **Local-first:** notas, acervo e histórico ficam no aparelho. A nuvem (Firebase) é opcional.
- **IA opcional (BYOD):** DeepSeek com a chave pessoal do usuário; sem chave/offline,
  o motor determinístico continua funcionando. Não há LLM on-device nem chave central.

## Portas de entrada (Home → FAB `+`)

1. **Importar esboço S-34** — arquivo DOCX/PDF/JWPUB/EPUB/RTF/TXT → parser
   estrutural → seções, subpontos e referências persistidos. O Copilot recebe a
   estrutura (objetivo, ponto atual, refs).
2. **Colar parte da reunião V&M** — texto da apostila/parte S-38 → detecta o tipo
   (discurso, demonstração) → cria **INTRO + N BODY + CONCLUSÃO**. A parte
   “O que você diria?” (consideração) é rejeitada com aviso.
3. **Criar esboço do zero** — discurso (estrutura S-34) ou demonstração
   (diálogo Publicador/Morador).

“Nota rápida” continua como ação secundária para uma nota em branco.

## Fluxo principal

1. Abrir/importar um esboço (S-34) ou colar uma parte V&M, ou começar do zero.
2. Escrever/desenvolver no editor multi-seção WYSIWYG (autosave).
3. Consultar o acervo local (publicações indexadas, JWPUB com página/§).
4. Pedir ajuda ao Copilot (DeepSeek BYOD ou motor offline).
5. Ensaiar no Ensaio (teleprompter, TTS Piper offline, STT) e usar a Tribuna.
6. Exportar ou apresentar.

## O que NÃO É

- **Não é gerador automático de discurso.** O app não substitui o preparo pessoal,
  a pesquisa nem a decisão do orador; ele orienta e propõe.
- **Não é multi-LLM.** A única IA remota é o DeepSeek (BYOD). Gemini/Groq/Qwen/
  Gemma/fallback/circuit breaker foram removidos.
- **Não é app web.** O código ativo é Android/Compose. Resíduos web
  (`public/`, `tools/e2e-*.cjs`, `vite.config.ts`, `tsconfig*.json`) são legado
  congelado, sem build.
- **Não é rede social.** Sem feed, comentários ou compartilhamento público.
- **Não baixa publicações sozinho.** O acervo é importado manualmente; downloads
  do jw.org são auxiliados, não automáticos.
- **Não é multi-dispositivo em tempo real.** O sync (Firestore) é opcional e
  não é colaborativo.
- **Não tem folders/tags/cores/pin como recurso fechado.** O schema Room está
  dormente; a UI atual usa lista simples. A remoção/reativação é **decisão
  pendente**, não uma promessa de produto.
- **Não é distribuído pela Play Store nesta fase.** Sideload para um grupo
  pequeno de amigos; privacidade/backup/keystore ainda precisam ser fechados.

## Decisões de escopo

| Tema | Decisão |
|---|---|
| Web v1 | Removido do produto; resíduos de build marcados como legado |
| Multi-LLM | Removido; DeepSeek-only (BYOD) |
| Consideração V&M (“O que você diria?”) | Rejeitada com aviso; não gera nota |
| Estrutura S-34 | Persistida (seções/subpontos/refs); intro/conclusão são camada do editor |
| Folders/tags/cores/pin | **Decisão pendente** (schema dormente; não remover sem decisão) |
| Ensaio + TTS/STT | Mantido (Piper “Jeff” offline; fallback nativo) |
| Firebase sync | Mantido como opcional (metadados; sem google-services.json = 100% local) |
| Export PDF/HTML | Mantido |
| Verifier | Warn-only no chat; Nível 2 cobre refs do S-34 |

## Critérios de sucesso (curto prazo)

1. Um amigo consegue instalar o APK, importar um S-34 real e preparar um discurso
   sem instruções externas.
2. Colar uma parte V&M real produz a estrutura esperada (N+3) com texto e refs.
3. O Copilot responde no ponto ativo, com citações apoiadas no acervo.
4. Ensaio/TTS e Tribuna funcionam no aparelho de teste (Redmi 9 / A34 / tablet).
5. Nenhuma perda de dados em uso normal; backup e chave de API protegidos antes
   da distribuição.

## Documentos relacionados

- [FREEZE.md](FREEZE.md) — itens congelados e decisões pendentes
- [parser-formatos-colagem.md](parser-formatos-colagem.md) — formatos de colagem
- [validacao-2026-10-25-redmi9.md](reports/validacao-2026-10-25-redmi9.md) — validação 1b + S-34
- [F19_B2_S34_PARSER.md](F19_B2_S34_PARSER.md) — parser S-34
- [F19_B6_S34_IMPORT_ACCEPTANCE.md](F19_B6_S34_IMPORT_ACCEPTANCE.md) — aceitação da importação
