# F20-C — Validação real do LLM, iteração e transições encadeadas

## 1. Provider real usado

**Nenhum.** O ambiente não tem credencial de provider (`env` sem `VITE_LLM_*`,
sem `.env`), o dispositivo SM-A346M não estava conectado e a fase proíbe
criar/gravar chave. Toda a validação foi **determinística**, com provider
fake capturando o corpo HTTP real e o parser/validador/proposta reais.

```text
LLM real = NOT_EXECUTED
physical validation = UNAVAILABLE
```

## 2. Plataforma

Web (Vitest + Dexie real) e Android (JUnit + fakes), com os mesmos contratos.

## 3. Modelo configurado

Nenhum modelo foi chamado. O default continua `gemini-3.6-flash` (F18) e
só é usado quando há chave do usuário. Nenhum segredo no diff.

## 4. Número de execuções reais

**0.** Contagens determinísticas: 41 testes novos
(16 web + 25 Android), 924 asserções de contrato sobre prompts/propostas.

## 5. Resultados

| Item | Resultado | Evidência |
|---|---|---|
| Introduction | PASS | prompt com objetivo + primeiro ponto + `[TRAINING] introduction` |
| Development | PASS | ponto atual, subpontos e refs do ponto; ponto 3 NÃO entra |
| Transition 1→2 | PASS | transição do ponto 1 é válida (bug corrigido, ver §7) |
| Transition 2→3 | PASS | atual + seguinte, `anterior=sec-1` |
| Conclusion | PASS | objetivo + último ponto |
| Iteração "melhore" | PASS | herda modo + ponto |
| Iteração "deixe mais natural" | PASS | mesmo ponto, mesmas fontes |
| Iteração "encurte" | PASS | mesmo ponto, sem informação nova |
| Mudança de modo | PASS | "agora desenvolva o ponto 2" troca de introdução para development/sec-2 |
| Mudança de ponto | PASS | sec-2 → sec-3 com as referências acompanhando |
| Stale | PASS | hash novo por proposta; edição manual bloqueia aplicação |
| Accept | PASS | `applyEditProposal` aplica; editor recebe via F5 |
| Reject | PASS | nada muda; estrutura intacta |
| Undo | PASS | `EditHistory` existente restaura |
| F6 | NOT_EXECUTED (sem provider) | verificador existente intacto |

## 6. Falhas encontradas e corrigidas

1. **Transição do ponto 1 era bloqueada.** A B.5 exigia "ponto anterior" para
   transição, mas transição = **atual → seguinte**: do ponto 1 para o 2 é
   válida e não tem anterior. Corrigido nas duas plataformas; teste
   `transitionNoPrimeiroPontoEvalida`.
2. **Comparação de referência por igualdade exata.** O rótulo do S-34 é a
   LINHA inteira ("Leia Tiago 2:17."), então citar "Tiago 2:17" era reportado
   como invenção. Agora a autorização é por **contenção** normalizada.
3. (F20-B, revalidado aqui) o ponto seguinte não é mais renderizado em todos
   os modos — só a transição o recebe.

## 7. Transições

- `TRANSITION` = ponto atual → ponto seguinte; só o seguinte é obrigatório.
- O prompt recebe **as duas ideias reais** (corpo, subpontos e referências do
  atual e do seguinte) e a instrução de não criar argumento novo nem alterar
  a ordem.
- Encadeamento validado na sequência completa (§44): intro → 1 → 1→2 → 2 →
  2→3 → 3 → conclusão, cada etapa com modo e ponto corretos e `spec` montável.

## 8. Iteração

Camada nova mínima (`OratorySession` / `oratorySession.ts`), pura e volátil:

```text
pedido explícito de modo  → manda (troca de modo/ponto)
refinamento               → HERDA modo + ponto da última geração
refinamento sem anterior  → estado amigável (não inventa alvo)
"crie um ponto 4"         → fora do escopo estrutural (S-34 intacto)
```

O histórico continua sendo o do chat; nada de memória paralela, nada de
copiar a geração anterior para dentro do prompt.

## 9. F6

Não executado (sem provider). O verificador, os 4 estados e o Content Guard
permanecem **intactos** — nenhuma linha alterada nesta fase. A geração entra
por `LlmRequest.oratorySpec` e a proposta resultante é a mesma estrutura F5
que o F6 já consome.

## 10. Stale

Cada proposta nova recebe `baseHashes` capturados no momento da geração
(`parseEditProposal` + `captureBaseHashes`). Edição manual posterior ⇒
`STALE_PROPOSAL` e nada é aplicado. Testado nos dois sentidos (aplicar
limpo / bloquear após edição).

## 11. Física

**UNAVAILABLE** — `adb devices` vazio, sem anúncio mDNS, sem emulador
executável no ambiente (fora do grupo `kvm`). Nada foi simulado.

## 12. Verificação objetiva da geração (§§37-39)

`OratoryFidelityCheck` / `oratoryFidelityCheck`: contagens, nunca score.

```text
inventedReferences     referências citadas fora do contexto autorizado
leakedReferences       referências do ponto SEGUINTE vazando para este
unsupportedNumbers     números sem apoio literal no conteúdo autorizado
```

Limitação honesta: só o ponto seguinte expõe referências no `Spec`, então
vazamento de outros pontos é reportado como "inventada" — os dois rótulos são
falha de fidelidade e o relatório os distingue.

## 13. Métrica operacional (execução determinística)

```text
real LLM runs .................... 0
invariantes estruturais testadas .. 41 testes
falhas ........................... 0
referências inventadas (fixture) .. 0
vazamentos entre pontos (fixture) . 0
afirmações factuais sem apoio ..... 0 (insumo para o F6)
```

## 14. Limitações

- Sem LLM real: a fidelidade do **modelo** não foi observada; a do
  **pipeline** (estrutura, fontes, isolamento, alvo, stale, proposta) está
  provada por contrato e provider fake.
- `OratorySession` é volátil por design (mesma política da F15/F17): morre
  com o processo, sem persistência nova.
- Vazamento de referências só é detectável para o ponto seguinte no `Spec`.
- Iteração é sempre `REPLACE` sobre o mesmo alvo; nunca troca de modo sem
  pedido explícito.
