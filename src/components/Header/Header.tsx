import { FolderOpen, Plus, Download, Settings, Sparkles, Play } from 'lucide-react';

interface HeaderProps {
  onOpenSpeechList: () => void;
  onNewSpeech: () => void;
  onOpenExport: () => void;
  onOpenSettings: () => void;
  onToggleCopilot: () => void;
  isCopilotOpen: boolean;
  onOpenTeleprompter: () => void;
}

export const Header = ({
  onOpenSpeechList,
  onNewSpeech,
  onOpenExport,
  onOpenSettings,
  onToggleCopilot,
  isCopilotOpen,
  onOpenTeleprompter,
}: HeaderProps) => {
  return (
    <header className="app-header">
      {/* Brand */}
      <div className="header-brand" onClick={onOpenSpeechList} title="Ver lista de discursos">
        <img src="/favicon.svg" alt="Better Talker" className="brand-icon" />
        <div className="brand-title">
          Better <span>Talker</span>
        </div>
      </div>

      {/* Header Actions */}
      <div className="header-actions">
        <button
          type="button"
          className="btn-secondary"
          onClick={onOpenSpeechList}
          title="Abrir discursos salvos no dispositivo"
        >
          <FolderOpen size={16} />
          <span className="hide-mobile">Discursos</span>
        </button>

        <button
          type="button"
          className="btn-secondary"
          onClick={onNewSpeech}
          title="Criar novo discurso"
        >
          <Plus size={16} />
          <span className="hide-mobile">Novo</span>
        </button>

        <button
          type="button"
          className="btn-secondary"
          onClick={onOpenExport}
          title="Exportar ou imprimir fichas de palco (Cue Cards)"
        >
          <Download size={16} />
          <span className="hide-mobile">Exportar</span>
        </button>

        <button
          type="button"
          className="btn-secondary"
          onClick={onOpenSettings}
          title="Configurações (Chave API Gemini, WPM padrão)"
        >
          <Settings size={16} />
        </button>

        {/* Copilot Toggle */}
        <button
          type="button"
          className={`btn-secondary ${isCopilotOpen ? 'active' : ''}`}
          onClick={onToggleCopilot}
          title="Alternar Copilot de Oratória"
          style={isCopilotOpen ? { borderColor: 'var(--primary)', color: 'var(--primary)' } : {}}
        >
          <Sparkles size={16} />
          <span className="hide-mobile">Copilot</span>
        </button>

        {/* Stage Rehearsal / Teleprompter Mode */}
        <button
          type="button"
          className="stage-mode-btn"
          onClick={onOpenTeleprompter}
          title="Iniciar Modo Palco / Teleprompter em tela cheia"
        >
          <Play size={16} fill="#0f172a" />
          <span>Modo Palco</span>
        </button>
      </div>
    </header>
  );
};
