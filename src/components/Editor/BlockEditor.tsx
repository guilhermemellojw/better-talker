import { useRef, useEffect } from 'react';
import type { Speech, SpeechBlock, SpeechCategory, StageCueDefinition } from '../../types/speech';
import { StageCueBar } from './StageCueBar';
import { FloatingFormatToolbar } from './FloatingFormatToolbar';
import { Clock, Tag, ChevronLeft, ChevronRight } from 'lucide-react';

interface BlockEditorTabsProps {
  speech: Speech;
  activeBlockId: string;
  onActiveBlockChange: (blockId: string) => void;
  onBlockChange: (blockId: string, patch: Partial<SpeechBlock>) => void;
  onSpeechChange: (patch: Partial<Speech>) => void;
}

export const BlockEditorTabs = ({
  speech,
  activeBlockId,
  onActiveBlockChange,
  onBlockChange,
  onSpeechChange,
}: BlockEditorTabsProps) => {
  const editorRef = useRef<HTMLDivElement>(null);
  const lastHtmlRef = useRef<string>('');
  const savedSelectionRef = useRef<Range | null>(null);

  const activeBlock = speech.blocks.find((b) => b.id === activeBlockId) || speech.blocks[0];
  const activeIndex = speech.blocks.findIndex((b) => b.id === activeBlockId);

  // Sync editor content when active block changes
  useEffect(() => {
    if (!editorRef.current || !activeBlock) return;
    if (activeBlock.contentHtml !== lastHtmlRef.current) {
      editorRef.current.innerHTML = activeBlock.contentHtml;
      lastHtmlRef.current = activeBlock.contentHtml;
    }
  }, [activeBlock?.id, activeBlock?.contentHtml]);

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
    if (!editorRef.current || !activeBlock) return;
    const newHtml = editorRef.current.innerHTML;
    lastHtmlRef.current = newHtml;
    const plainText = newHtml.replace(/<[^>]+>/g, '').replace(/\u00A0/g, ' ').trim();
    onBlockChange(activeBlock.id, { contentHtml: newHtml, plainText });
    onSpeechChange({
      contentHtml: speech.blocks.map((b) => b.id === activeBlock.id ? `<h2>${b.title}</h2><p>${b.plainText}</p>` : b.contentHtml).join('<hr/>'),
    });
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
    if (!sel || sel.rangeCount === 0 || sel.isCollapsed) return;

    const range = sel.getRangeAt(0);
    const selectedText = range.extractContents();
    const mark = document.createElement('mark');
    mark.className = colorClass;
    mark.appendChild(selectedText);
    range.insertNode(mark);
    range.setStartAfter(mark);
    range.collapse(true);
    sel.removeAllRanges();
    sel.addRange(range);
    handleInput();
  };

  const handleInsertCue = (cue: StageCueDefinition) => {
    if (!editorRef.current || !activeBlock) return;
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

    const badge = document.createElement('span');
    badge.className = `stage-cue-badge ${cue.badgeClass}`;
    badge.setAttribute('data-cue-type', cue.id);
    badge.setAttribute('contenteditable', 'false');
    badge.innerHTML = `${cue.icon} ${cue.label}`;

    const spaceNode = document.createTextNode('\u00A0');
    range.insertNode(spaceNode);
    range.insertNode(badge);
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



  if (!activeBlock) return null;

  return (
    <div className="editor-workspace" onMouseUp={saveCurrentSelection} onKeyUp={saveCurrentSelection}>
      <div className="editor-card">
        <div className="speech-title-container">
          <input
            type="text"
            className="speech-title-input"
            value={speech.title}
            onChange={(e) => onSpeechChange({ title: e.target.value })}
            placeholder="Título do Discurso..."
          />
        </div>

        <div className="speech-meta-tags">
          <div className="category-chip">
            <Tag size={13} />
            <select
              value={speech.category}
              onChange={(e) => onSpeechChange({ category: e.target.value as SpeechCategory })}
              style={{ background: 'transparent', border: 'none', color: 'inherit', font: 'inherit', fontWeight: 700, cursor: 'pointer', padding: 0 }}
            >
              <option value="ted">TED Talk / Inspirador</option>
              <option value="pitch">Pitch de Negócios / Vendas</option>
              <option value="keynote">Palestra / Keynote</option>
              <option value="debate">Debate / Argumentação</option>
              <option value="cerimonia">Cerimônia / Homenagem</option>
              <option value="geral">Geral</option>
            </select>
          </div>
          <div className="target-badge">
            <Clock size={14} />
            <span>Meta:</span>
            <input
              type="number"
              min="1"
              max="120"
              value={speech.targetDurationMinutes}
              onChange={(e) => onSpeechChange({ targetDurationMinutes: Math.max(1, parseInt(e.target.value) || 1) })}
              style={{ width: '52px', padding: '0.15rem 0.4rem', fontSize: '0.85rem', textAlign: 'center' }}
            />
            <span>min</span>
          </div>
        </div>

        <div className="block-tabs">
          {speech.blocks.map((block, idx) => (
            <button
              key={block.id}
              type="button"
              className={`block-tab ${block.id === activeBlockId ? 'active' : ''}`}
              onClick={() => onActiveBlockChange(block.id)}
            >
              <span className="block-tab-minutes">{block.minutes} min</span>
              <span className="block-tab-title">{block.title}</span>
              {idx > 0 && <ChevronLeft size={12} />}
              {idx < speech.blocks.length - 1 && <ChevronRight size={12} />}
            </button>
          ))}
        </div>

        <FloatingFormatToolbar
          onFormat={handleFormat}
          onApplyHighlight={handleApplyHighlight}
          onInsertStructure={handleInsertStructure}
        />

        <StageCueBar onInsertCue={handleInsertCue} />

        <div
          ref={editorRef}
          className="rich-text-canvas"
          contentEditable
          suppressContentEditableWarning
          onInput={handleInput}
          onBlur={handleInput}
          dangerouslySetInnerHTML={{ __html: activeBlock.contentHtml }}
          data-placeholder="Comece a escrever este bloco..."
        />

        <div className="block-footer">
          <span>Bloco {activeIndex + 1} de {speech.blocks.length}</span>
          <span>{activeBlock.minutes} min</span>
        </div>
      </div>
    </div>
  );
};
