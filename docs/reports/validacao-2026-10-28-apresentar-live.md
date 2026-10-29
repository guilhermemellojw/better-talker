# Apresentar live — implementação inicial e validação (2026-10-28)

- Branch: `feat/v2.11-apresentar-live` (base `084eba3`).
- Spec: `docs/specs/apresentar-live.md`.
- Dispositivos: A34 `SM-A346M` (1080x2340) e tablet `25040RP0AL`
  (1600x2560, retrato/paisagem).
- Suíte: **2.622 testes / 0 falhas**. `lintRelease`: **verde**.

## O que foi implementado

### Hub

- Cada nota mostra a prontidão da Tribuna: **Tribuna pronta**,
  **Tribuna incompleta** ou **Falta gerar Tribuna**.
- Nota sem Tribuna **não abre palco vazio**: abre um diálogo com
  “Abrir editor” (onde fica ⋮ → Gerar Tribuna).
- Nota incompleta abre o pré-voo com aviso por seção.
- `HubViewModel.refresh()` recalcula prontidão/duração; o hub atualiza ao
  voltar do editor/Tribuna.

### Pré-voo

- Cues | Roteiro (Roteiro só em demonstrações; padrão em demo).
- Começar do início | Continuar da seção salva | Escolher seção.
- Timer visível/oculto.
- Fonte A−/A+ com amostra da frase-âncora.
- Orientação: Retrato (padrão) | Automática | Travar.
- Meu papel no roteiro (demonstrações).
- Aviso de bateria baixa quando não está carregando.
- Checklist curto (tela acesa, imersivo, notificações, orientação).
- Acesso à Tribuna/editor; nada é editado no palco.

### Palco

- Rota própria `present/{noteId}`, read-only, imersiva e com `keepScreenOn`.
- Cues: uma seção por vez; indicador `2/6`, título, timer total e tempo/meta
  da seção; âncora em destaque; ideia central; texto-chave; transição.
- Roteiro (demo): turno atual em destaque, próximo turno menor, papel do
  usuário destacado; toque/swipe avança turno/seção.
- Navegação manual Anterior/Próxima; sem auto-avanço.
- Timer pausa em `ON_PAUSE` (chamada/perda de foco/tela) e exige
  “Tempo pausado — Retomar”; rotação por config change não pausa.
- Sair pelo X/voltar pede confirmação; a seção/tempo ficam salvos no
  DataStore para “Continuar de onde parei” no próximo pré-voo.
- Sem TTS/STT/chat/métricas/gravação no palco; funciona offline.

## Validação no A34

- Hub: notas de teste mostraram **Tribuna pronta**, **incompleta** e
  **falta gerar**; tocar na sem Tribuna abriu o diálogo para o editor.
- Cues:
  - seção 1/3, âncora, ideia, texto-chave e transição visíveis;
  - timer total + meta 02:00/restante; “Próxima” foi para 2/3 e resetou a
    meta da seção para 04:00;
  - timer oculto removeu os relógios;
  - X/voltar abriu a confirmação; sair salvou a seção 2 e o pré-voo passou a
    mostrar **Continuar da seção 2**.
- Ciclo de vida: Home → voltar ao app mostrou **Tempo pausado** com
  **Retomar**; o timer ficou congelado até retomar.
- Demo: pré-voo abriu em **Roteiro**, papel **Ana**, turno atual + próximo,
  turno 2/4 permaneceu na Cena 1, turno 3/4 mudou para Cena 2 e resetou o
  timer da seção.
- Incompleta: 2/3 com aviso; ao avançar para a 3ª seção o palco mostrou
  **“Sem Tribuna nesta seção”** e permitiu continuar.
- Nenhum áudio foi gerado; nenhum crash. Notas de teste removidas e #192
  preservada; rotação do sistema restaurada.

## Validação no tablet (25040RP0AL)

- Hub: prontidão por card em grade; **Tribuna pronta**, **incompleta** e
  **falta gerar** visíveis.
- Pré-voo: cues, começar/continuar, timer, fonte, orientação, bateria;
  entrada no palco.
- Palco: seção 1/3 com âncora/ideia/ref/transição, timer total e meta da
  seção; “Próxima” foi para 2/3 e resetou a meta.
- Ciclo de vida: Home → voltar mostrou **Tempo pausado / Retomar**; retomou
  sem auto-start.
- “Continuar da seção 2” disponível no pré-voo após sair.
- **Paisagem:** com orientação Automática, `wm user-rotation lock 1` rotacionou
  o palco para 2560x1600; a seção 2 e o timer foram preservados, layout
  respondeu bem. Depois da saída, orientação e settings do sistema foram
  restaurados.
- Demo: Roteiro no tablet, papel Ana, turno atual/próximo e contador de turno.
- Notas de teste removidas; #192 preservada.

## Pendências

- **Swipe:** implementado; os botões Anterior/Próxima foram validados nos
  dois aparelhos. A rotação foi validada no tablet; no A34 o sistema estava
  com `ignoreOrientationRequest`, por isso ficou retrato.
- **Bateria baixa:** código preparado; não exercitado com bateria <20%.
- **Continuar de onde parei:** existe no pré-voo; ainda não há atalho direto
  no card do hub.
- **Bug do chat/Tribuna vazia:** não foi alterado nesta primeira entrega.
- **Merge/release:** nada do Apresentar vai para a `main` antes da tag
  `v2.9.0/28`; a keystore continua o gate externo.
