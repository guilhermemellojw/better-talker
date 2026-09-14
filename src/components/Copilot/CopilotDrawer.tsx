import { useState } from 'react';
import type { Speech, SpeechMetrics, CopilotSuggestion, SpeechBlock } from '../../types/speech';
import { queryGeminiOratoryCoach } from '../../services/geminiService';
import { Sparkles, X, Wand2, Copy, Check, PlusCircle } from 'lucide-react';

interface CopilotDrawerProps {
  isOpen: boolean;
  onClose: () => void;
  speech: Speech;
  metrics: SpeechMetrics;
  apiKey: string;
  offlineSuggestions: CopilotSuggestion[];
  activeBlock?: SpeechBlock;
  onInsertTextIntoSpeech: (text: string) => void;
  contextPassages?: string[];
}

export const CopilotDrawer = ({
  isOpen,
  onClose,
  speech,
  metrics,
  apiKey,
  offlineSuggestions,
  activeBlock,
  onInsertTextIntoSpeech,
  contextPassages = [],
}: CopilotDrawerProps) => {
  const [activeTone, setActiveTone] = useState<'ted' | 'pitch' | 'motivational' | 'academic' | 'humorous'>('ted');
  const [isLoading, setIsLoading] = useState(false);
  const [resultText, setResultText] = useState<string | null>(null);
  const [resultTitle, setResultTitle] = useState<string>('');
  const [copied, setCopied] = useState(false);

  const hasApiKey = Boolean(apiKey && apiKey.trim().length > 0);

  const handleRunAIAction = async (
    action: 'hook' | 'rewrite' | 'critique' | 'cues' | 'shorten',
    title: string
  ) => {
    setIsLoading(true);
    setResultTitle(title);
    setResultText(null);
    const textToAnalyze = activeBlock?.plainText || speech.plainText || speech.title;

    try {
      const res = await queryGeminiOratoryCoach({
        apiKey,
        text: textToAnalyze,
        action,
        tone: activeTone,
        contextPassages,
      });
      setResultText(res);
    } catch (err) {
      console.error(err);
      setResultText('Ocorreu um erro ao processar com a IA. Tente novamente.');
    } finally {
      setIsLoading(false);
    }
  };

  const handleCopy = () => {
    if (resultText) {
      navigator.clipboard.writeText(resultText);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  };

  return (
    <aside className={`copilot-panel ${isOpen ? 'open' : ''}`}>
      {/* Header */}
      <div className="copilot-header">
        <div className="copilot-title-group">
          <Sparkles size={20} style={{ color: 'var(--primary)' }} />
          <div>
            <h3>Copilot de Oratória</h3>
            <span style={{ fontSize: '0.72rem', color: 'var(--text-tertiary)' }}>
              {metrics.wordCount} palavras • {metrics.formattedEstimatedTime}
            </span>
          </div>
          <span className={`copilot-mode-badge ${hasApiKey ? 'gemini' : 'offline'}`}>
            {hasApiKey ? '✨ Gemini AI' : '⚡ Motor Offline'}
          </span>
        </div>
        <button type="button" className="modal-close-btn" onClick={onClose} title="Fechar painel">
          <X size={18} />
        </button>
      </div>

      <div className="copilot-body">
        {/* Tone Selector */}
        <div className="tone-picker-container">
          <span className="tone-picker-label">Tom Desejado para Palco:</span>
          <div className="tone-pills">
            {[
              { id: 'ted', label: 'TED / Inspirador' },
              { id: 'pitch', label: 'Pitch de Negócios' },
              { id: 'motivational', label: 'Motivacional' },
              { id: 'humorous', label: 'Descontraído' },
              { id: 'academic', label: 'Corporativo / Formal' },
            ].map((t) => (
              <button
                key={t.id}
                type="button"
                className={`tone-pill-btn ${activeTone === t.id ? 'active' : ''}`}
                onClick={() => setActiveTone(t.id as any)}
              >
                {t.label}
              </button>
            ))}
          </div>
        </div>

        {/* Quick Action Grid */}
        <div>
          <span className="tone-picker-label" style={{ display: 'block', marginBottom: '0.4rem' }}>
            Ações Rápidas de Palco:
          </span>
          <div className="ai-actions-grid">
            <button
              type="button"
              className="ai-action-btn"
              onClick={() => handleRunAIAction('hook', '🎣 3 Ganchos de Abertura')}
              disabled={isLoading}
            >
              <span className="btn-icon">🎣</span>
              <span className="btn-title">Criar Ganchos</span>
              <span className="btn-desc">Primeiros 30s magnéticos</span>
            </button>

            <button
              type="button"
              className="ai-action-btn"
              onClick={() => handleRunAIAction('rewrite', `🎭 Ajustar Tom (${activeTone.toUpperCase()})`)}
              disabled={isLoading}
            >
              <span className="btn-icon">🎭</span>
              <span className="btn-title">Ajustar Tom</span>
              <span className="btn-desc">Reescrever para fala oral</span>
            </button>

            <button
              type="button"
              className="ai-action-btn"
              onClick={() => handleRunAIAction('critique', '🔍 Raio-X de Oratória')}
              disabled={isLoading}
            >
              <span className="btn-icon">🔍</span>
              <span className="btn-title">Raio-X de Palco</span>
              <span className="btn-desc">Crítica & pontos fortes</span>
            </button>

            <button
              type="button"
              className="ai-action-btn"
              onClick={() => handleRunAIAction('cues', '⚡ Inserir Marcadores')}
              disabled={isLoading}
            >
              <span className="btn-icon">⚡</span>
              <span className="btn-title">Marcadores IA</span>
              <span className="btn-desc">Pausas e ênfases no texto</span>
            </button>
          </div>
        </div>

        {/* Loading Indicator */}
        {isLoading && (
          <div
            style={{
              padding: '1.5rem',
              textAlign: 'center',
              background: 'var(--bg-surface-elevated)',
              borderRadius: 'var(--radius-md)',
              border: '1px solid var(--border-subtle)',
            }}
          >
            <Wand2 size={24} className="spin-animation" style={{ color: 'var(--primary)', margin: '0 auto 0.5rem' }} />
            <div style={{ fontSize: '0.88rem', fontWeight: 600 }}>O Copilot está refinando a oratória...</div>
            <div style={{ fontSize: '0.75rem', color: 'var(--text-tertiary)' }}>Analisando cadência e impacto vocal</div>
          </div>
        )}

        {/* AI Action Result Box */}
        {resultText && !isLoading && (
          <div className="ai-result-box">
            <div className="ai-result-header">
              <h4>{resultTitle}</h4>
              <div className="ai-result-actions">
                <button type="button" className="action-btn-sm" onClick={handleCopy} title="Copiar texto">
                  {copied ? <Check size={14} style={{ color: '#10b981' }} /> : <Copy size={14} />}
                  <span>{copied ? 'Copiado!' : 'Copiar'}</span>
                </button>
                <button
                  type="button"
                  className="action-btn-sm primary"
                  onClick={() => onInsertTextIntoSpeech(resultText)}
                  title="Inserir diretamente no discurso"
                >
                  <PlusCircle size={14} />
                  <span>Inserir</span>
                </button>
              </div>
            </div>
            <div className="ai-result-content" dangerouslySetInnerHTML={{ __html: resultText }} />
          </div>
        )}

        {/* Real-time Offline Suggestions Cards */}
        <div>
          <span className="tone-picker-label" style={{ display: 'block', marginBottom: '0.6rem' }}>
            Dicas Retóricas em Tempo Real ({offlineSuggestions.length}):
          </span>
          <div className="copilot-suggestions-list">
            {offlineSuggestions.map((sug) => (
              <div key={sug.id} className="suggestion-card">
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <span className="sug-category">{sug.categoryLabel}</span>
                  {sug.replacementText && (
                    <button
                      type="button"
                      style={{ fontSize: '0.75rem', color: 'var(--primary)', fontWeight: 600 }}
                      onClick={() => onInsertTextIntoSpeech(sug.replacementText!)}
                    >
                      + Usar Exemplo
                    </button>
                  )}
                </div>
                <div className="sug-title">{sug.title}</div>
                <div className="sug-content">{sug.content}</div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </aside>
  );
};
