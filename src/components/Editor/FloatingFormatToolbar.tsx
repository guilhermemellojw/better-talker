import { Bold, Italic, Underline, Heading2, List, ListOrdered, Minus, Sparkles } from 'lucide-react';

interface FloatingFormatToolbarProps {
  onFormat: (command: string, value?: string) => void;
  onApplyHighlight: (colorClass: string) => void;
  onInsertStructure: (structureType: 'hook' | 'story' | 'arguments' | 'climax' | 'cta') => void;
}

export const FloatingFormatToolbar = ({
  onFormat,
  onApplyHighlight,
  onInsertStructure,
}: FloatingFormatToolbarProps) => {
  return (
    <div className="floating-format-toolbar">
      {/* Basic Text Formatting */}
      <button
        type="button"
        className="format-btn"
        onClick={() => onFormat('bold')}
        title="Negrito (Ctrl+B)"
      >
        <Bold size={16} />
      </button>
      <button
        type="button"
        className="format-btn"
        onClick={() => onFormat('italic')}
        title="Itálico (Ctrl+I)"
      >
        <Italic size={16} />
      </button>
      <button
        type="button"
        className="format-btn"
        onClick={() => onFormat('underline')}
        title="Sublinhado (Ctrl+U)"
      >
        <Underline size={16} />
      </button>

      <div className="toolbar-divider" />

      {/* Headings and Lists */}
      <button
        type="button"
        className="format-btn"
        onClick={() => onFormat('formatBlock', '<h2>')}
        title="Título de Seção (H2)"
      >
        <Heading2 size={16} />
      </button>
      <button
        type="button"
        className="format-btn"
        onClick={() => onFormat('insertUnorderedList')}
        title="Lista com Marcadores"
      >
        <List size={16} />
      </button>
      <button
        type="button"
        className="format-btn"
        onClick={() => onFormat('insertOrderedList')}
        title="Lista Numerada (Regra de Três)"
      >
        <ListOrdered size={16} />
      </button>
      <button
        type="button"
        className="format-btn"
        onClick={() => onFormat('insertHorizontalRule')}
        title="Divisor de Bloco"
      >
        <Minus size={16} />
      </button>

      <div className="toolbar-divider" />

      {/* Stage Highlight Markers */}
      <div className="highlight-picker" title="Marca-texto para Entonação de Palco">
        <span style={{ fontSize: '0.75rem', color: 'var(--text-tertiary)', marginRight: '2px' }}>Realce:</span>
        <button
          type="button"
          className="hl-dot hl-amber-dot"
          onClick={() => onApplyHighlight('hl-amber')}
          title="Amarelo (Atenção / Ênfase Normal)"
        />
        <button
          type="button"
          className="hl-dot hl-emerald-dot"
          onClick={() => onApplyHighlight('hl-emerald')}
          title="Verde (Storytelling / Conexão / Calma)"
        />
        <button
          type="button"
          className="hl-dot hl-rose-dot"
          onClick={() => onApplyHighlight('hl-rose')}
          title="Vermelho (Clímax / Ponto Alto / Alerta)"
        />
        <button
          type="button"
          className="hl-dot hl-purple-dot"
          onClick={() => onApplyHighlight('hl-purple')}
          title="Roxo (Pergunta Retórica / Metáfora)"
        />
      </div>

      <div className="toolbar-divider" />

      {/* Structure Blocks Helper */}
      <div style={{ display: 'flex', gap: '0.3rem' }}>
        <button
          type="button"
          className="cue-insert-btn"
          style={{ fontSize: '0.78rem', padding: '0.2rem 0.6rem' }}
          onClick={() => onInsertStructure('hook')}
          title="Inserir Bloco de Gancho Inicial"
        >
          <Sparkles size={13} style={{ color: 'var(--primary)' }} /> + Gancho
        </button>
        <button
          type="button"
          className="cue-insert-btn"
          style={{ fontSize: '0.78rem', padding: '0.2rem 0.6rem' }}
          onClick={() => onInsertStructure('story')}
          title="Inserir Bloco de História"
        >
          + História
        </button>
        <button
          type="button"
          className="cue-insert-btn"
          style={{ fontSize: '0.78rem', padding: '0.2rem 0.6rem' }}
          onClick={() => onInsertStructure('climax')}
          title="Inserir Bloco de Clímax"
        >
          + Clímax
        </button>
      </div>
    </div>
  );
};
