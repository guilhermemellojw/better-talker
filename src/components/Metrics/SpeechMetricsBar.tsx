import { useState } from 'react';
import type { SpeechMetrics } from '../../types/speech';
import { Clock, MessageSquare, AlertTriangle, Activity, PauseCircle } from 'lucide-react';

interface SpeechMetricsBarProps {
  metrics: SpeechMetrics;
  targetMinutes: number;
  wpm: number;
  onWpmChange: (newWpm: number) => void;
  isSaving: boolean;
}

export const SpeechMetricsBar = ({
  metrics,
  targetMinutes,
  wpm,
  onWpmChange,
  isSaving,
}: SpeechMetricsBarProps) => {
  const [showFillersDetail, setShowFillersDetail] = useState(false);

  const isOverTime = metrics.estimatedTimeSeconds > targetMinutes * 60;

  return (
    <div className="metrics-bar">
      {/* Left group: Words, Pauses, Estimated Time */}
      <div className="metrics-group">
        <div className="metric-item">
          <MessageSquare size={15} />
          <span>Palavras:</span>
          <strong>{metrics.wordCount}</strong>
        </div>

        {/* Estimated Speaking Time */}
        <div
          className={`metric-pill time-pill ${isOverTime ? 'overtime' : ''}`}
          title={`Estimado com base em ${wpm} palavras por minuto + ${metrics.totalPauseDurationSeconds}s de pausas`}
        >
          <Clock size={14} />
          <span>Tempo de Fala:</span>
          <strong>{metrics.formattedEstimatedTime}</strong>
          <span style={{ opacity: 0.7, fontSize: '0.78rem' }}>/ {targetMinutes}m meta</span>
        </div>

        {/* Stage Pauses */}
        {metrics.pauseCount > 0 && (
          <div className="metric-item" title="Tempo total de pausas e silêncios planejados">
            <PauseCircle size={15} style={{ color: 'var(--cue-pause-text)' }} />
            <span>Pausas:</span>
            <strong>{metrics.pauseCount} ({metrics.totalPauseDurationSeconds}s)</strong>
          </div>
        )}

        {/* Pace WPM Selector */}
        <div className="pace-selector" title="Ajuste o ritmo estimado da sua fala">
          <span style={{ fontSize: '0.75rem', color: 'var(--text-tertiary)' }}>Ritmo:</span>
          <select value={wpm} onChange={(e) => onWpmChange(Number(e.target.value))}>
            <option value={110}>Calmo (110 ppm)</option>
            <option value={130}>Normal (130 ppm)</option>
            <option value={160}>Enérgico (160 ppm)</option>
          </select>
        </div>
      </div>

      {/* Right group: Fillers, Readability, Autosave indicator */}
      <div className="metrics-group">
        {/* Fillers detector */}
        {metrics.totalFillers > 0 ? (
          <div
            className="metric-pill fillers-pill"
            style={{ cursor: 'pointer', position: 'relative' }}
            onClick={() => setShowFillersDetail(!showFillersDetail)}
            title="Vícios de linguagem detectados"
          >
            <AlertTriangle size={14} />
            <span>{metrics.totalFillers} vícios orais</span>
            {showFillersDetail && (
              <div
                style={{
                  position: 'absolute',
                  bottom: '120%',
                  right: 0,
                  background: 'var(--bg-surface-elevated)',
                  border: '1px solid var(--border-highlight)',
                  borderRadius: 'var(--radius-md)',
                  padding: '0.6rem 0.8rem',
                  boxShadow: 'var(--shadow-lg)',
                  width: '200px',
                  zIndex: 100,
                  color: 'var(--text-primary)',
                }}
              >
                <div style={{ fontWeight: 700, fontSize: '0.78rem', marginBottom: '0.3rem', color: 'var(--primary)' }}>
                  Vícios Encontrados:
                </div>
                {metrics.fillerWordsFound.map((f) => (
                  <div key={f.word} style={{ fontSize: '0.75rem', display: 'flex', justifyContent: 'space-between' }}>
                    <span>"{f.word}"</span>
                    <strong>{f.count}x</strong>
                  </div>
                ))}
              </div>
            )}
          </div>
        ) : null}

        {/* Cadence Ease */}
        <div className="metric-pill ease-pill" title="Fluidez para respiração em palco">
          <Activity size={14} />
          <span>{metrics.readingEase}</span>
        </div>

        {/* Autosave offline indicator */}
        <div className="autosave-status" title="Seus dados são salvos localmente no navegador / dispositivo em tempo real">
          <div className={`status-dot ${isSaving ? 'saving' : ''}`} />
          <span>{isSaving ? 'Salvando...' : 'Salvo Offline'}</span>
        </div>
      </div>
    </div>
  );
};
