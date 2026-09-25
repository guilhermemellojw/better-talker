# Fase 11 — Auditoria UX (antes de qualquer alteração)

Data: ciclo pós-v1.0 (HEAD `0505432`). Método: leitura de código + screenshots
reais (desktop 1440px e mobile 390px, Chrome headless, perfil limpo).

## Fluxos auditados

### A — Primeiro uso: abrir → entender → criar → editar
- Boot mostra editor vazio + Copilot aberto. Sem onboarding; placeholders
  ("Título do Discurso...", "Comece a escrever este bloco...") orientam o básico. OK.
- "Criar discurso" (header: Discursos/Novo) evidente. Importar evidente no modal.
- Copilot NÃO é explicado: painel abre com tone pills e 4 ações sem dizer
  o que fazem com o texto (P2).
- Editor × Copilot: relação clara o suficiente (Inserir/Aceitar). OK.

### B — Importação: arquivo → blocos → editor
- Formatos aceitos, feedback imediato (novo discurso ativo). OK.
- Limitação conhecida (.docx binário) fora do escopo F11.

### C — Preparação: editor → F9 → sugestão → verificar → aceitar
- Funciona, mas **ações primárias enterradas**: ordem do painel é
  evidência → Verificar → Análise → Tom → Ações rápidas → Edição assistida
  → resultado → sugestões. O loop principal (Tom + Ações) fica no meio de
  uma coluna longa (P1).
- Preview ANTES/DEPOIS + Aceitar/Rejeitar claros. Verificação com
  proveniência expansível clara. OK.

### D — Copilot: perguntar → fonte → decidir
- Estados 📖/🎤/relevância com tooltips e texto (não só cor/ícone). OK.
- Microcopy técnica ausente nos erros (friendlyError PT). OK.
- Inconsistência: teleprompter usa "WPM" no header e "PPM" na caixa de
  velocidade; barra de métricas usa "ppm" (P2).

### E — Ensaio: teleprompter → cue cards → tempo
- Teleprompter: controles com labels + títulos de teclado, espelho, zoom,
  reset, progresso. OK. `controlsVisible` é estado morto (sempre true) —
  cosmético, fora do escopo alterar comportamento; registrado.
- Cue cards com fallback para texto corrido. OK.

## Problemas classificados

- **P0**: `LibraryModal` ignora `isOpen` — modal cobre o app em todo boot
  (único modal sem o guard; provado por screenshot).
- **P1**: sem adicionar/remover blocos manualmente (só via import/Copilot);
  minutos/título do bloco não editáveis (título/minutos: backlog, só add/delete agora).
- **P1**: header estoura em 390px (10 ações sem wrap/scroll; classe
  `hide-mobile` usada mas sem regra CSS).
- **P1**: ações rápidas do Copilot enterradas no meio do painel.
- **P2**: "WPM" vs "ppm" no teleprompter.
- **P2**: Copilot sem explicação de primeiro uso (o que cada ação faz com o texto).
- P3/refinamentos: densidade do painel, onboarding guiado, edição de
  minutos/título por bloco → backlog pós-fase.

## O que NÃO será tocado

Retrieval, F6, categorias, F9, providers, evidências, hashes/stale, Room,
persistência, offline, segurança, sync, Android nativo (só web nesta fase;
nenhum arquivo `android/` será alterado).

## Plano de commits pequenos

1. `fix(ux): LibraryModal respects isOpen` (P0).
2. `feat(ux): copilot quick actions first + first-use hint` (P1/P2).
3. `feat(ux): add/remove speech blocks manually` (P1).
4. `feat(ux): responsive header + teleprompter ppm microcopy` (P1/P2).
