import { useState } from 'react';
import type { AppSettings } from '../../types/speech';
import { X, Key, ShieldCheck, Cpu } from 'lucide-react';

interface SettingsModalProps {
  isOpen: boolean;
  onClose: () => void;
  settings: AppSettings;
  onSaveSettings: (settings: AppSettings) => void;
}

export const SettingsModal = ({
  isOpen,
  onClose,
  settings,
  onSaveSettings,
}: SettingsModalProps) => {
  const [apiKey, setApiKey] = useState(settings.geminiApiKey || '');
  const [defaultWpm, setDefaultWpm] = useState(settings.defaultWpm || 130);

  if (!isOpen) return null;

  const handleSave = () => {
    onSaveSettings({
      ...settings,
      geminiApiKey: apiKey.trim(),
      defaultWpm,
    });
    onClose();
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal-dialog" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h3>
            <Key size={20} style={{ color: 'var(--primary)' }} />
            Configurações do Better Talker
          </h3>
          <button type="button" className="modal-close-btn" onClick={onClose}>
            <X size={20} />
          </button>
        </div>

        <div className="modal-body">
          {/* Gemini API Key */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: '0.4rem' }}>
            <label style={{ fontSize: '0.85rem', fontWeight: 700, color: 'var(--text-primary)' }}>
              Chave de API do Google Gemini (Opcional):
            </label>
            <input
              type="password"
              placeholder="Cole sua chave AI Studio (AIzaSy...)"
              value={apiKey}
              onChange={(e) => setApiKey(e.target.value)}
            />
            <span style={{ fontSize: '0.78rem', color: 'var(--text-tertiary)' }}>
              Sua chave fica gravada apenas no seu dispositivo (IndexedDB local) e nunca é enviada para servidores intermediários.
            </span>
          </div>

          {/* Offline Engine Info Box */}
          <div
            style={{
              background: 'rgba(56, 189, 248, 0.1)',
              border: '1px solid rgba(56, 189, 248, 0.25)',
              padding: '0.85rem',
              borderRadius: 'var(--radius-md)',
              display: 'flex',
              gap: '0.75rem',
              alignItems: 'flex-start',
            }}
          >
            <Cpu size={22} style={{ color: 'var(--cue-pause-text)', flexShrink: 0, marginTop: '2px' }} />
            <div style={{ fontSize: '0.82rem', color: 'var(--text-secondary)', lineHeight: 1.5 }}>
              <strong style={{ color: 'var(--cue-pause-text)' }}>Motor 100% Offline Ativo:</strong> Mesmo sem chave de API ou sem conexão com a internet, todas as funcionalidades de oratória, sugestões de gancho, métricas de tempo, detecção de vícios e teleprompter continuam funcionando localmente.
            </div>
          </div>

          {/* Default WPM */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: '0.4rem' }}>
            <label style={{ fontSize: '0.85rem', fontWeight: 700, color: 'var(--text-primary)' }}>
              Ritmo Padrão de Fala (WPM / Palavras por Minuto):
            </label>
            <select
              value={defaultWpm}
              onChange={(e) => setDefaultWpm(Number(e.target.value))}
            >
              <option value={110}>110 PPM (Calmo, solene, reflexivo)</option>
              <option value={130}>130 PPM (Padrão para TED Talks e palestras fluídas)</option>
              <option value={160}>160 PPM (Enérgico, dinâmico, pitch de vendas)</option>
            </select>
          </div>

          {/* Security note */}
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: '0.5rem',
              fontSize: '0.8rem',
              color: 'var(--text-tertiary)',
            }}
          >
            <ShieldCheck size={16} style={{ color: '#10b981' }} />
            <span>Todos os dados e textos são armazenados 100% Offline-First.</span>
          </div>
        </div>

        <div className="modal-footer">
          <button type="button" className="btn-secondary" onClick={onClose}>
            Cancelar
          </button>
          <button type="button" className="btn-primary" onClick={handleSave}>
            Salvar Configurações
          </button>
        </div>
      </div>
    </div>
  );
};
