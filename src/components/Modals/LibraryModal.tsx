import { useState, useEffect } from 'react';
import { speechStorage } from '../../services/db';
import { indexPublication, getPublications } from '../../services/libraryIndexer';
import type { Publication } from '../../types/speech';
import { X, FileText, FileUp, Trash2, CheckCircle, Clock } from 'lucide-react';

interface LibraryModalProps {
  isOpen: boolean;
  onClose: () => void;
  onImportComplete?: () => void;
}

export const LibraryModal = ({ isOpen, onClose, onImportComplete }: LibraryModalProps) => {
  const [publications, setPublications] = useState<Publication[]>([]);
  const [loading, setLoading] = useState(false);
  const [importing, setImporting] = useState(false);

  const loadPublications = async () => {
    const list = await getPublications();
    setPublications(list);
  };

  useEffect(() => {
    if (isOpen) loadPublications();
  }, [isOpen]);

  const handleImport = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = e.target.files;
    if (!files || files.length === 0) return;
    setLoading(true);
    setImporting(true);
    for (let i = 0; i < files.length; i++) {
      const file = files[i];
      const kind = file.name.endsWith('.epub') ? 'epub' : 'pdf';
      await indexPublication(file, kind);
    }
    setImporting(false);
    await loadPublications();
    setLoading(false);
    onImportComplete?.();
  };

  const handleDelete = async (id: string) => {
    await speechStorage.deletePublication(id);
    await loadPublications();
  };

  const formatDate = (ts: number) => {
    return new Date(ts).toLocaleDateString('pt-BR', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' });
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal-dialog modal-lg" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h3>
            <FileText size={20} style={{ color: 'var(--primary)' }} />
            Acervo Local ({publications.length})
          </h3>
          <button type="button" className="modal-close-btn" onClick={onClose}>
            <X size={20} />
          </button>
        </div>

        <div className="modal-body">
          <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: '1rem', gap: '0.5rem' }}>
            <button
              type="button"
              className="btn-primary"
              onClick={() => {
                const input = document.createElement('input');
                input.type = 'file';
                input.accept = '.epub,.pdf';
                input.multiple = true;
                input.onchange = (ev) => handleImport(ev as unknown as React.ChangeEvent<HTMLInputElement>);
                input.click();
              }}
              disabled={importing}
            >
              <FileUp size={16} />
              <span>{importing ? 'Indexando...' : 'Importar EPUB/PDF'}</span>
            </button>
          </div>

          {loading ? (
            <p>Carregando acervo...</p>
          ) : publications.length === 0 ? (
            <p style={{ textAlign: 'center', color: 'var(--text-tertiary)', padding: '2rem' }}>
              Nenhuma publicação no acervo. Importe um arquivo .epub ou .pdf baixado do jw.org.
            </p>
          ) : (
            <div className="speech-list">
              {publications.map((pub) => (
                <div key={pub.id} className="speech-item-card">
                  <div className="speech-item-info">
                    <h4>{pub.title}</h4>
                    <div className="speech-item-meta">
                      <span className="category-chip">{pub.kind.toUpperCase()}</span>
                      {pub.indexed ? (
                        <span style={{ color: '#10b981', fontSize: '0.75rem', display: 'flex', alignItems: 'center', gap: '4px' }}>
                          <CheckCircle size={12} /> Indexado
                        </span>
                      ) : (
                        <span style={{ color: 'var(--text-tertiary)', fontSize: '0.75rem', display: 'flex', alignItems: 'center', gap: '4px' }}>
                          <Clock size={12} /> Indexando...
                        </span>
                      )}
                      <span style={{ fontSize: '0.75rem', color: 'var(--text-tertiary)' }}>
                        {formatDate(pub.addedAt)}
                      </span>
                    </div>
                  </div>
                  <div className="speech-item-actions" onClick={(e) => e.stopPropagation()}>
                    <button
                      type="button"
                      className="modal-close-btn"
                      onClick={() => handleDelete(pub.id)}
                      title="Remover do acervo"
                      style={{ color: '#ef4444' }}
                    >
                      <Trash2 size={16} />
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}
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
