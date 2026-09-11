import { useRef, useEffect } from 'react';
import type { Speech, SpeechCategory, StageCueDefinition } from '../../types/speech';
import { StageCueBar } from './StageCueBar';
import { FloatingFormatToolbar } from './FloatingFormatToolbar';
import { Clock, Tag } from 'lucide-react';

interface RichSpeechEditorProps {
  speech: Speech;
  onChange: (updated: Partial<Speech>) => void;
  onOpenCopilotWithSelection?: (selectedText: string) => void;
}

export const RichSpeechEditor = ({
  speech,
  onChange,
}: RichSpeechEditorProps) => {
  const editorRef = useRef<HTMLDivElement>(null);
  const lastHtmlRef = useRef<string>(speech.contentHtml);
  const savedSelectionRef = useRef<Range | null>(null);

  // Sync speech content if a completely different speech is loaded
  useEffect(() => {
    if (editorRef.current && editorRef.current.innerHTML !== speech.contentHtml) {
      // Only set if different to prevent cursor jumps
      if (speech.contentHtml !== lastHtmlRef.current) {
        editorRef.current.innerHTML = speech.contentHtml;
        lastHtmlRef.current = speech.contentHtml;
      }
    }
  }, [speech.id, speech.contentHtml]);

  // Save selection before clicking toolbars
  const saveCurrentSelection = () => {
    const sel = window.getSelection();
    if (sel && sel.rangeCount > 0) {
      savedSelectionRef.current = sel.getRangeAt(0).cloneRange();
    }
  };

  const restoreSelection = () => {
    const sel = window.getSelection();
    if (sel && savedSelectionRef.current) {
      sel.removeAllRanges();
      sel.addRange(savedSelectionRef.current);
    }
  };

  const handleInput = () => {
    if (!editorRef.current) return;
    const newHtml = editorRef.current.innerHTML;
    lastHtmlRef.current = newHtml;
    onChange({ contentHtml: newHtml });
  };

  const handleFormat = (command: string, value: string = '') => {
    if (!editorRef.current) return;
    editorRef.current.focus();
    restoreSelection();
    document.execCommand(command, false, value);
    handleInput();
  };

  const handleApplyHighlight = (colorClass: string) => {
    if (!editorRef.current) return;
    editorRef.current.focus();
    restoreSelection();

    const sel = window.getSelection();
    if (!sel || sel.rangeCount === 0 || sel.isCollapsed) {
      return;
    }

    const range = sel.getRangeAt(0);
    const selectedText = range.extractContents();
    const mark = document.createElement('mark');
    mark.className = colorClass;
    mark.appendChild(selectedText);
    range.insertNode(mark);

    // Reposition cursor after mark
    range.setStartAfter(mark);
    range.collapse(true);
    sel.removeAllRanges();
    sel.addRange(range);

    handleInput();
  };

  const handleInsertCue = (cue: StageCueDefinition) => {
    if (!editorRef.current) return;
    editorRef.current.focus();
    restoreSelection();

    const sel = window.getSelection();
    let range: Range;

    if (sel && sel.rangeCount > 0) {
      range = sel.getRangeAt(0);
    } else {
      range = document.createRange();
      range.selectNodeContents(editorRef.current);
      range.collapse(false);
    }

    // Create Stage Cue Badge DOM element
    const badge = document.createElement('span');
    badge.className = `stage-cue-badge ${cue.badgeClass}`;
    badge.setAttribute('data-cue-type', cue.id);
    badge.setAttribute('contenteditable', 'false');
    badge.innerHTML = `${cue.icon} ${cue.label}`;

    // Space after badge
    const spaceNode = document.createTextNode('\u00A0');

    range.insertNode(spaceNode);
    range.insertNode(badge);

    // Position cursor after the inserted badge and space
    range.setStartAfter(spaceNode);
    range.collapse(true);
    if (sel) {
      sel.removeAllRanges();
      sel.addRange(range);
    }

    handleInput();
  };

  const handleInsertStructure = (structureType: 'hook' | 'story' | 'arguments' | 'climax' | 'cta') => {
    if (!editorRef.current) return;
    editorRef.current.focus();
    restoreSelection();

    let structureHtml = '';
    switch (structureType) {
      case 'hook':
        structureHtml = `<h2>🎯 <strong>O Gancho de Abertura</strong></h2><p>Você já se perguntou por que... <span class="stage-cue-badge cue-pause" data-cue-type="pause-2s" contenteditable="false">⏸ 2s Pausa</span></p>`;
        break;
      case 'story':
        structureHtml = `<h2>📖 <strong>História e Conexão Humana</strong></h2><p>Lembro-me de uma situação que mudou tudo... <span class="stage-cue-badge cue-whisper" data-cue-type="whisper" contenteditable="false">🤫 Tom Intimista</span></p>`;
        break;
      case 'arguments':
        structureHtml = `<h2>💡 <strong>Os 3 Pontos Centrais</strong></h2><ol><li><strong>Primeiro:</strong> ...</li><li><strong>Segundo:</strong> ...</li><li><strong>Terceiro:</strong> ...</li></ol>`;
        break;
      case 'climax':
        structureHtml = `<h2>🔥 <strong>O Clímax do Discurso</strong></h2><p><mark class="hl-rose">Este é o momento de maior impacto.</mark> <span class="stage-cue-badge cue-emphasis" data-cue-type="emphasis" contenteditable="false">⚡ Ênfase Máxima</span></p>`;
        break;
      case 'cta':
        structureHtml = `<h2>🚀 <strong>Chamada para Ação & Encerramento</strong></h2><p>Portanto, façam valer cada momento. Muito obrigado! <span class="stage-cue-badge cue-applause" data-cue-type="applause" contenteditable="false">👏 Pausa para Aplausos</span></p>`;
        break;
    }

    document.execCommand('insertHTML', false, structureHtml);
    handleInput();
  };

  return (
    <div className="editor-workspace" onMouseUp={saveCurrentSelection} onKeyUp={saveCurrentSelection}>
      <div className="editor-card">
        {/* Title Input */}
        <div className="speech-title-container">
          <input
            type="text"
            className="speech-title-input"
            value={speech.title}
            onChange={(e) => onChange({ title: e.target.value })}
            placeholder="Título do Discurso..."
          />
        </div>

        {/* Metadata Controls */}
        <div className="speech-meta-tags">
          <div className="category-chip">
            <Tag size={13} />
            <select
              value={speech.category}
              onChange={(e) => onChange({ category: e.target.value as SpeechCategory })}
              style={{
                background: 'transparent',
                border: 'none',
                color: 'inherit',
                font: 'inherit',
                fontWeight: 700,
                cursor: 'pointer',
                padding: 0,
              }}
            >
              <option value="ted" style={{ background: '#111827' }}>TED Talk / Inspirador</option>
              <option value="pitch" style={{ background: '#111827' }}>Pitch de Negócios / Vendas</option>
              <option value="keynote" style={{ background: '#111827' }}>Palestra / Keynote</option>
              <option value="debate" style={{ background: '#111827' }}>Debate / Argumentação</option>
              <option value="cerimonia" style={{ background: '#111827' }}>Cerimônia / Homenagem</option>
              <option value="geral" style={{ background: '#111827' }}>Geral</option>
            </select>
          </div>

          <div className="target-badge">
            <Clock size={14} />
            <span>Meta de tempo:</span>
            <input
              type="number"
              min="1"
              max="120"
              value={speech.targetDurationMinutes}
              onChange={(e) => onChange({ targetDurationMinutes: Math.max(1, parseInt(e.target.value) || 1) })}
              style={{
                width: '52px',
                padding: '0.15rem 0.4rem',
                fontSize: '0.85rem',
                textAlign: 'center',
              }}
            />
            <span>minutos</span>
          </div>
        </div>

        {/* Floating Format Toolbar */}
        <FloatingFormatToolbar
          onFormat={handleFormat}
          onApplyHighlight={handleApplyHighlight}
          onInsertStructure={handleInsertStructure}
        />

        {/* Stage Cue Badges Bar */}
        <StageCueBar onInsertCue={handleInsertCue} />

        {/* Editable Rich Canvas */}
        <div
          ref={editorRef}
          className="rich-text-canvas"
          contentEditable
          suppressContentEditableWarning
          onInput={handleInput}
          onBlur={handleInput}
          dangerouslySetInnerHTML={{ __html: speech.contentHtml }}
          data-placeholder="Comece a escrever ou estruture seu discurso com os botões de gancho e marcadores de palco acima..."
        />
      </div>
    </div>
  );
};
