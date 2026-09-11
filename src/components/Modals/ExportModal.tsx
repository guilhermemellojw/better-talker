import { useState } from 'react';
import type { Speech, SpeechMetrics } from '../../types/speech';
import { X, Printer, Download, Copy, Check, Layers, FileCode } from 'lucide-react';
import { extractPlainTextFromHtml } from '../../services/rhetoricEngine';

interface ExportModalProps {
  isOpen: boolean;
  onClose: () => void;
  speech: Speech;
  metrics: SpeechMetrics;
}

export const ExportModal = ({
  isOpen,
  onClose,
  speech,
  metrics,
}: ExportModalProps) => {
  const [tab, setTab] = useState<'cards' | 'raw'>('cards');
  const [copied, setCopied] = useState(false);

  if (!isOpen) return null;

  const plainText = extractPlainTextFromHtml(speech.contentHtml);

  const handlePrint = () => {
    window.print();
  };

  const handleDownloadFile = (format: 'txt' | 'md') => {
    let content = '';
    if (format === 'md') {
      content = `# ${speech.title}\n\n**Categoria:** ${speech.category} | **Tempo Estimado:** ${metrics.formattedEstimatedTime} (${metrics.wordCount} palavras)\n\n---\n\n${plainText}`;
    } else {
      content = `${speech.title.toUpperCase()}\nTempo Estimado: ${metrics.formattedEstimatedTime} (${metrics.wordCount} palavras)\n\n${plainText}`;
    }

    const blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `${speech.title.toLowerCase().replace(/[^a-z0-9]/g, '_') || 'discurso'}.${format}`;
    link.click();
    URL.revokeObjectURL(url);
  };

  const handleCopy = () => {
    navigator.clipboard.writeText(plainText);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  // Split speech into sections for Cue Cards (cards de púlpito)
  const paragraphs = speech.contentHtml
    .split(/<hr\s*\/?>|<h2>/i)
    .map((block) => block.replace(/<\/h2>/i, '').trim())
    .filter(Boolean);

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal-dialog modal-lg" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <div style={{ display: 'flex', gap: '0.8rem', alignItems: 'center' }}>
            <h3 style={{ margin: 0 }}>Exportar & Fichas de Palco</h3>
            <div style={{ display: 'flex', gap: '0.4rem' }}>
              <button
                type="button"
                className={`cue-insert-btn ${tab === 'cards' ? 'active' : ''}`}
                onClick={() => setTab('cards')}
              >
                <Layers size={14} /> Fichas de Púlpito
              </button>
              <button
                type="button"
                className={`cue-insert-btn ${tab === 'raw' ? 'active' : ''}`}
                onClick={() => setTab('raw')}
              >
                <FileCode size={14} /> Arquivo / Texto
              </button>
            </div>
          </div>
          <button type="button" className="modal-close-btn" onClick={onClose}>
            <X size={20} />
          </button>
        </div>

        <div className="modal-body">
          {tab === 'cards' ? (
            <div>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
                <span style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
                  Fichas pautadas ideais para levar ao palco ou púlpito durante apresentações:
                </span>
                <button type="button" className="btn-primary" onClick={handlePrint}>
                  <Printer size={16} />
                  <span>Imprimir / Salvar PDF</span>
                </button>
              </div>

              <div className="cue-cards-grid">
                {paragraphs.length > 0 ? (
                  paragraphs.map((p, idx) => (
                    <div key={idx} className="cue-card-item">
                      <h5>Ficha {idx + 1}: {idx === 0 ? 'Abertura & Gancho' : `Bloco ${idx + 1}`}</h5>
                      <div
                        dangerouslySetInnerHTML={{ __html: p }}
                        style={{ fontSize: '0.9rem', lineHeight: 1.5 }}
                      />
                    </div>
                  ))
                ) : (
                  <div className="cue-card-item">
                    <h5>Ficha 1: Discurso Geral</h5>
                    <p>{plainText.slice(0, 300)}...</p>
                  </div>
                )}
              </div>
            </div>
          ) : (
            <div>
              <div style={{ display: 'flex', gap: '0.6rem', marginBottom: '1.25rem' }}>
                <button type="button" className="btn-secondary" onClick={() => handleDownloadFile('txt')}>
                  <Download size={15} /> Baixar TXT
                </button>
                <button type="button" className="btn-secondary" onClick={() => handleDownloadFile('md')}>
                  <Download size={15} /> Baixar Markdown (.md)
                </button>
                <button type="button" className="btn-secondary" onClick={handleCopy}>
                  {copied ? <Check size={15} style={{ color: '#10b981' }} /> : <Copy size={15} />}
                  <span>{copied ? 'Copiado!' : 'Copiar Texto'}</span>
                </button>
              </div>

              <textarea
                readOnly
                value={plainText}
                style={{
                  width: '100%',
                  height: '240px',
                  fontFamily: 'var(--font-mono)',
                  fontSize: '0.9rem',
                  lineHeight: 1.6,
                  resize: 'none',
                }}
              />
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
