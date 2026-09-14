import type { Speech } from '../../types/speech';
import { X, Plus, Trash2, Calendar, Clock, FileText, FileInput } from 'lucide-react';

interface SpeechListModalProps {
  isOpen: boolean;
  onClose: () => void;
  speeches: Speech[];
  activeSpeechId: string;
  onSelectSpeech: (speech: Speech) => void;
  onNewSpeech: () => void;
  onDeleteSpeech: (id: string) => void;
  onImportOutline: (buffer: ArrayBuffer) => void;
}

export const SpeechListModal = ({
  isOpen,
  onClose,
  speeches,
  activeSpeechId,
  onSelectSpeech,
  onNewSpeech,
  onDeleteSpeech,
  onImportOutline,
}: SpeechListModalProps) => {
  if (!isOpen) return null;

  const formatDate = (timestamp: number) => {
    return new Date(timestamp).toLocaleDateString('pt-BR', {
      day: '2-digit',
      month: 'short',
      hour: '2-digit',
      minute: '2-digit',
    });
  };

  const handleImport = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = (ev) => {
      const buffer = ev.target?.result as ArrayBuffer;
      onImportOutline(buffer);
    };
    reader.readAsArrayBuffer(file);
    e.target.value = '';
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal-dialog modal-lg" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h3>
            <FileText size={20} style={{ color: 'var(--primary)' }} />
            Meus Discursos ({speeches.length})
          </h3>
          <button type="button" className="modal-close-btn" onClick={onClose}>
            <X size={20} />
          </button>
        </div>

        <div className="modal-body">
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '0.5rem', flexWrap: 'wrap' }}>
            <button
              type="button"
              className="btn-primary"
              onClick={() => { onNewSpeech(); onClose(); }}
            >
              <Plus size={16} />
              <span>Novo Discurso</span>
            </button>
            <button
              type="button"
              className="btn-secondary"
              onClick={() => {
                const input = document.createElement('input');
                input.type = 'file';
                input.accept = '.docx,.rtf,.pdf,.jwpub';
                input.multiple = false;
                input.onchange = (ev) => handleImport(ev as unknown as React.ChangeEvent<HTMLInputElement>);
                input.click();
              }}
              title="Importar esboço (.docx, .rtf, .pdf)"
            >
              <FileInput size={16} />
              <span>Importar Esboço</span>
            </button>
          </div>

          <div className="speech-list">
            {speeches.map((sp) => {
              const isActive = sp.id === activeSpeechId;
              return (
                <div
                  key={sp.id}
                  className={`speech-item-card ${isActive ? 'active-speech' : ''}`}
                  onClick={() => {
                    onSelectSpeech(sp);
                    onClose();
                  }}
                >
                  <div className="speech-item-info">
                    <h4>{sp.title || 'Discurso Sem Título'}</h4>
                    <div className="speech-item-meta">
                      <span style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
                        <Clock size={13} /> {sp.targetDurationMinutes} min
                      </span>
                      <span style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
                        <Calendar size={13} /> {formatDate(sp.updatedAt)}
                      </span>
                      <span className="category-chip" style={{ fontSize: '0.7rem', padding: '0.1rem 0.5rem' }}>
                        {sp.category.toUpperCase()}
                      </span>
                      {sp.blocks.length > 0 && (
                        <span className="category-chip" style={{ fontSize: '0.7rem', padding: '0.1rem 0.5rem', background: 'var(--primary)', color: '#fff' }}>
                          {sp.blocks.length} blocos
                        </span>
                      )}
                    </div>
                  </div>

                  <div className="speech-item-actions" onClick={(e) => e.stopPropagation()}>
                    {speeches.length > 1 && (
                      <button
                        type="button"
                        className="modal-close-btn"
                        onClick={() => {
                          if (window.confirm(`Deseja excluir "${sp.title}"?`)) {
                            onDeleteSpeech(sp.id);
                          }
                        }}
                        title="Excluir discurso"
                        style={{ color: '#ef4444' }}
                      >
                        <Trash2 size={16} />
                      </button>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        </div>

        <div className="modal-footer">
          <button type="button" className="btn-secondary" onClick={onClose}>
            Fechar
          </button>
        </div>
      </div>
    </div>
  );
};
