# Amostra humana de 20 refs — C2.8 (2026-10-31)

**Base:** matriz re-executada com o detector real (`article`/`chapter`).  
**Resultado corrigido:** 391 refs · 375 de conteúdo · **349/375 = 93,1%** com texto no prompt · 156 RESOLVED · 193 PARTIAL · 6 TEXT_NOT_FOUND · 20 EDITION_MISSING · 16 TRAINING.  
**Amostra:** 11 “página sem § indexado”, 5 “página inteira”, 3 “§” e 1 “unidade de lição”; oversample proposital de livros/§.  
**Controles:** `cl 225 §15` (RESOLVED, 600 chars, resolver direto UNRESOLVED) e `jy 144 §7` (TEXT_NOT_FOUND).

## Resumo

| Veredito | Refs |
|---|---:|
| OK | **14** |
| PARCIAL | **3** |
| ERRADO | **3** |
| **Total** | **20** |

Os erros se concentram em livro/página/§, não nas revistas por página. O bucket PARTIAL não é homogêneo.

## Tabela

| # | Ref | Motivo | Status | Veredito | Observação |
|---|---|---|---|---|---|
| 01 | `‘Mantenha-se’` | old | PARTIAL | **OK** | Página 33-34 contém “QUANDO SURGEM PROBLEMAS” e a exortação a não se afastar da congregação; o trecho relevante está no meio da página. |
| 02 | `w98 15/2 26 §5` | old | PARTIAL | **OK** | Página 26 contém “encorajamento” e “consolar” (2 Coríntios 1:3-5) mais adiante; o início é outro subheading. |
| 03 | `w10 15/2 15 § 5` | old | PARTIAL | **OK** | Página 15 é o artigo da água da vida; “Jeová” e “convite” aparecem. |
| 04 | `w09 15/7 9 §§ 8-10` | old | PARTIAL | **OK** | Página 9 contém “Como a esposa mostra profundo respeito pelo marido” e o §8. |
| 05 | `w11 15/12 16-17 §§ 18-19` | old | PARTIAL | **ERRADO** | Páginas 16-17 são sobre resistir ao espírito do mundo; não há “conhecimento” nem “aplicar” no texto completo. A ref parece ser de outro artigo/página. |
| 06 | `w09 15/10 9 §§ 7-9` | old | PARTIAL | **OK** | Página 9 contém “Sede pacíficos” e a discussão de Romanos 12:18. |
| 07 | `w10 15/8 13-14 §§ 8-11` | old | PARTIAL | **OK** | Páginas 13-14 contêm “resgate”, “perdão” e “fé”. |
| 08 | `w15 15/10 20 §§ 7-8` | old | PARTIAL | **OK** | Página 20 contém “Não deixe que coisas desnecessárias...” e 1 Coríntios 7:29-31; §7 explícito. |
| 09 | `w07 15/9 26 §§ 1-2` | old | PARTIAL | **OK** | Página 26 é “Como praticar a misericórdia” e o bom samaritano. |
| 10 | `w15 15/2 8-9 §§ 14-16` | old | PARTIAL | **PARCIAL** | Páginas 8-9 são sobre compaixão/ternura; “humildade” só aparece em §18, depois do cap de 4.000 chars. O tema é próximo, mas o § citado não está no prompt. |
| 11 | `w16.09 29 §§ 3-5` | old | PARTIAL | **OK** | Página 29 é “CONHEÇA BEM OS SEUS FILHOS”; §3 e perguntas aos filhos. |
| 12 | `g93 22/1 9-11` | page | PARTIAL | **OK** | Páginas 9-11 são sobre a Terra arruinada; “Jeová” e “arruinam” aparecem. |
| 13 | `it-1 665` | page | PARTIAL | **PARCIAL** | Página 665 tem 10.022 chars; “Demas” só aparece em 3.625 (no fim do cap de 4.000), depois de conteúdo sobre “dedo”. O texto relevante entra, mas enterrado. |
| 14 | `w73 1/3 140-1` | page | PARTIAL | **OK** | Página 140 contém “missionário”, “isolado”, “oração” e a citação da prisão na China. |
| 15 | `w07 1/4 8-11` | page | PARTIAL | **OK** | Páginas 8-11 são sobre a profecia de Jesus e a destruição de Jerusalém. |
| 16 | `kj 142-4` | page | PARTIAL | **ERRADO** | Página 142 é sobre Ninrode/cristandade; não há “Jerusalém”, “destruição” ou “607”. Página errada. |
| 17 | `it-1 544 § 4` | § | PARTIAL | **ERRADO** | O fallback global de §4 pega “Filho de Sasaque...” (página 12), não o §4 de “conheciam a Jeová” na página 544. Texto de outro artigo. |
| 18 | `it “Termo” § 15` | § | PARTIAL | **PARCIAL** | O artigo “Termo” é o certo, mas o trecho sobre a Lei/judeus está em ~8.800 chars, além do cap de 4.000; o prompt só traz a definição de “termo”/fronteiras. |
| 19 | `lff lição 14 ponto 1` | § | PARTIAL | **OK** | Lição 14 §1 é sobre basear a adoração na Bíblia; “Bíblia”, “verdade” e “ensinos” aparecem. |
| 20 | `lff lição 22` | unit | PARTIAL | **OK** | Lição 22 é sobre falar as boas novas; “verdades” e “Bíblia” aparecem. |

## Achados para o código

1. **Fallback global de §** (`it-1 544`, `it-2 890`, `it-1 587`): quando a página tem § indexado mas nenhum casa, o resolvedor busca o § no livro inteiro e pega outro artigo. Deveria devolver UNRESOLVED ou limitar a busca ao artigo/seção da página.
2. **Página errada** (`w11 15/12`, `kj 142-4`): o índice de página aponta para outro artigo; a página não contém o tema citado.
3. **Cap de 4.000 chars** (`it “Termo”`, `w15 15/2`): o artigo é certo, mas o § citado está depois do cap; o prompt recebe a definição/parte inicial.
4. **Conteúdo enterrado** (`it-1 665`): “Demas” só aparece em 3.625/4.000, depois de conteúdo sobre “dedo”.
5. **Parser de livro `cl`**: `cl 225 §15` vira “estudo 225”; o resolver direto falha e o prompt recebe só 600 chars do fallback.
6. **Runner da matriz**: usava `DetectedRef` manual sem `article`/`chapter`; corrigido para `RefDetector.detect`, o que mudou 291→193 PARTIAL e 92,5%→93,1%.

O pacote com os textos completos do prompt e o contexto de cada ref está em `/tmp/opencode/amostra-20/` (local; não commitado por conter trechos das publicações).
