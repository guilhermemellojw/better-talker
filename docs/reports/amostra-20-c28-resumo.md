# Amostra humana de 20 refs — C2.8 / 3.0.6 (2026-10-31)

**Matriz 3.0.6:** 391 refs · 375 de conteúdo · **347/375 = 92,5%** com texto no prompt · 156 RESOLVED · 191 PARTIAL · 8 TEXT_NOT_FOUND · 20 EDITION_MISSING · 16 TRAINING.  
**Matriz 3.0.5 corrigida (antes das correções):** 349/375 = 93,1% · 193 PARTIAL · 6 TEXT_NOT_FOUND. A diferença é intencional: 3 §§ globais errados viraram TEXT_NOT_FOUND e 1 faixa (`it-1 749-51`) passou a resolver.  
**Amostra:** 11 “página sem § indexado”, 5 “página inteira”, 3 “§” e 1 “unidade de lição”; oversample proposital de livros/§.  
**Controles:** `cl 225 §15` e `cl 171 §8` agora RESOLVED com o § exato (869/839 chars); `it-1 749-51` agora PARTIAL (2.634 chars).

## Resumo

| Veredito | Refs |
|---|---:|
| OK | **18** |
| PARCIAL | **1** |
| SEM TEXTO | **1** |
| ERRADO | **0** |
| **Total** | **20** |

**Leitura:** 18/20 OK; 1 PARCIAL (`it-1 665`, “Demas” enterrado no fim do cap); 1 SEM TEXTO (`it-1 544 §4`, que antes devolvia o §4 de outro artigo). Nenhum ERRADO na amostra.

## Tabela

| # | Ref | Motivo | Status | Veredito | Observação |
|---|---|---|---|---|---|
| 01 | `‘Mantenha-se’` | old | PARTIAL | **OK** | Foco em §16-18: o texto começa em “Se de alguma forma um irmão nos magoar...” e contém a exortação a não se afastar da congregação. |
| 02 | `w98 15/2 26 §5` | old | PARTIAL | **OK** | Página 26 contém “encorajamento” e “consolar” (2 Coríntios 1:3-5) mais adiante; o início é outro subheading. |
| 03 | `w10 15/2 15 § 5` | old | PARTIAL | **OK** | Foco em §5: “De quem se origina o convite de ‘vir e tomar de graça a água da vida’”. |
| 04 | `w09 15/7 9 §§ 8-10` | old | PARTIAL | **OK** | Foco em §8-10: “As esposas cristãs têm em Jesus um modelo perfeito de submissão...”. |
| 05 | `w11 15/12 16-17 §§ 18-19` | old | PARTIAL | **OK** | Foco em §18-19: “Que exemplo Jesus nos deu em corresponder à influência do espírito de Jeová...”; a página 16-17 é o artigo certo. |
| 06 | `w09 15/10 9 §§ 7-9` | old | PARTIAL | **OK** | Foco em §7-9: “Sede pacíficos para com todos os homens” e a discussão de Romanos 12:18. |
| 07 | `w10 15/8 13-14 §§ 8-11` | old | PARTIAL | **OK** | Foco em §8-11: resgate, perdão e fé; “Adão e Eva foram criados perfeitos...”. |
| 08 | `w15 15/10 20 §§ 7-8` | old | PARTIAL | **OK** | Foco em §7-8: “Não deixe que coisas desnecessárias...” e 1 Coríntios 7:29-31. |
| 09 | `w07 15/9 26 §§ 1-2` | old | PARTIAL | **OK** | Foco em §1-2: o bom samaritano e “O tratamento que o samaritano deu ao homem ferido...”. |
| 10 | `w15 15/2 8-9 §§ 14-16` | old | PARTIAL | **OK** | Foco em §14-16: “Como podemos imitar a ternura de Jesus”, “Nossas ações” e “Nossas palavras”. |
| 11 | `w16.09 29 §§ 3-5` | old | PARTIAL | **OK** | Foco em §3-5: “Conheça bem os seus filhos...”; perguntas aos filhos. |
| 12 | `g93 22/1 9-11` | page | PARTIAL | **OK** | Faixa 9-11 com começo+fim: o texto inclui a conclusão sobre Deus salvar a Terra. |
| 13 | `it-1 665` | page | PARTIAL | **PARCIAL** | Página 665 tem 10.022 chars; “Demas” só aparece em 3.625/4.000, depois de conteúdo sobre “dedo”. O texto relevante entra, mas enterrado. |
| 14 | `w73 1/3 140-1` | page | PARTIAL | **OK** | Faixa 140-141 com começo+fim: inclui a citação do missionário isolado e a oração. |
| 15 | `w07 1/4 8-11` | page | PARTIAL | **OK** | Faixa 8-11 com começo+fim: inclui a profecia de Jesus e a confirmação histórica. |
| 16 | `kj 142-4` | page | PARTIAL | **OK** | Faixa 142-144 com começo+fim: o fim inclui “a vindoura destruição de Jerusalém e seu templo”; o começo é Ninrode/cristandade. |
| 17 | `it-1 544 § 4` | sem texto | TEXT_NOT_FOUND | **SEM TEXTO** | O fallback global de §4 foi removido: a página 544 não tem §4 e a edição tem índice de página, então o status correto é TEXT_NOT_FOUND (antes vinha “Filho de Sasaque”, de outro artigo). |
| 18 | `it “Termo” § 15` | § | PARTIAL | **OK** | Artigo “Termo” com começo+fim: o fim inclui “Por meio do pacto da Lei, Deus ‘separou Israel’...”, que é o contexto da citação. |
| 19 | `lff lição 14 ponto 1` | unit | PARTIAL | **OK** | Lição 14 §1: adoração baseada na Bíblia; “Bíblia”, “verdade” e “ensinos” aparecem. |
| 20 | `lff lição 22` | unit | PARTIAL | **OK** | Lição 22: falar as boas novas; “verdades” e “Bíblia” aparecem. |

## O que mudou no 3.0.6

1. **§ global só sem índice de página:** com página indexada, o resolvedor não busca o § no livro inteiro; devolve TEXT_NOT_FOUND em vez de outro artigo (`it-1 544`, `it-2 890`, `it-1 587`).
2. **Foco por § na página:** quando o índice antigo tem os §§ no início do texto, o prompt começa no § citado e vai até o próximo § fora do range (`w11`, `w15`, `w07`, etc.).
3. **Faixas abreviadas:** `142-4` → 142..144, `749-51` → 749..751, `140-1` → 140..141.
4. **`cl` é página, não “estudo”:** `cl 225 §15` agora resolve a página 225 §15 com o texto exato.
5. **Começo+fim:** faixas/artigos maiores que 4.000 chars mandam começo e fim; o trecho citado no fim não se perde (`kj 142-4`, `it “Termo”`, `g93`, `w73`).
6. **Runner:** usa o detector real (`article`/`chapter`), não a `DetectedRef` manual.

O pacote completo (contexto + texto integral) está em `~/btwork/amostra-20-c28/` (local; não commitado por conter trechos das publicações).
