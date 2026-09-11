import { useState, useEffect, useRef, useMemo } from 'react';
import type { Speech, AppSettings } from './types/speech';
import { speechStorage } from './services/db';
import {
  calculateSpeechMetrics,
  generateOfflineCopilotSuggestions,
  extractPlainTextFromHtml,
} from './services/rhetoricEngine';
import { Header } from './components/Header/Header';
import { RichSpeechEditor } from './components/Editor/RichSpeechEditor';
import { SpeechMetricsBar } from './components/Metrics/SpeechMetricsBar';
import { CopilotDrawer } from './components/Copilot/CopilotDrawer';
import { TeleprompterModal } from './components/Teleprompter/TeleprompterModal';
import { SpeechListModal } from './components/Modals/SpeechListModal';
import { SettingsModal } from './components/Modals/SettingsModal';
import { ExportModal } from './components/Modals/ExportModal';

export function App() {
  const [speeches, setSpeeches] = useState<Speech[]>([]);
  const [activeSpeech, setActiveSpeech] = useState<Speech | null>(null);
  const [settings, setSettings] = useState<AppSettings>({
    geminiApiKey: '',
    defaultWpm: 130,
    teleprompterFontSize: 36,
    teleprompterMirrored: false,
  });

  const [isSaving, setIsSaving] = useState(false);
  const [isCopilotOpen, setIsCopilotOpen] = useState(true);
  const [speechListOpen, setSpeechListOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [exportOpen, setExportOpen] = useState(false);
  const [teleprompterOpen, setTeleprompterOpen] = useState(false);

  const autosaveTimerRef = useRef<number | null>(null);

  // Initialize DB and load data
  useEffect(() => {
    async function initData() {
      const loadedSettings = await speechStorage.getSettings();
      setSettings(loadedSettings);

      const loadedSpeeches = await speechStorage.getAllSpeeches();
      setSpeeches(loadedSpeeches);
      if (loadedSpeeches.length > 0) {
        setActiveSpeech(loadedSpeeches[0]);
      }
    }
    initData();
  }, []);

  // Compute live speech metrics
  const metrics = useMemo(() => {
    if (!activeSpeech) {
      return calculateSpeechMetrics('', 130, 5);
    }
    return calculateSpeechMetrics(
      activeSpeech.contentHtml,
      activeSpeech.targetWpm || settings.defaultWpm || 130,
      activeSpeech.targetDurationMinutes || 5
    );
  }, [activeSpeech?.contentHtml, activeSpeech?.targetWpm, activeSpeech?.targetDurationMinutes, settings.defaultWpm]);

  // Compute real-time offline rhetoric suggestions
  const offlineSuggestions = useMemo(() => {
    if (!activeSpeech) return [];
    const plainText = extractPlainTextFromHtml(activeSpeech.contentHtml);
    return generateOfflineCopilotSuggestions(activeSpeech.title, plainText, metrics);
  }, [activeSpeech?.title, activeSpeech?.contentHtml, metrics]);

  // Handle Speech Updates with Debounced Autosave
  const handleSpeechChange = (updatedFields: Partial<Speech>) => {
    if (!activeSpeech) return;

    const updatedSpeech: Speech = {
      ...activeSpeech,
      ...updatedFields,
      updatedAt: Date.now(),
    };

    if (updatedFields.contentHtml !== undefined) {
      updatedSpeech.plainText = extractPlainTextFromHtml(updatedFields.contentHtml);
    }

    setActiveSpeech(updatedSpeech);
    setSpeeches((prev) =>
      prev.map((s) => (s.id === updatedSpeech.id ? updatedSpeech : s))
    );

    // Debounced autosave
    setIsSaving(true);
    if (autosaveTimerRef.current) {
      clearTimeout(autosaveTimerRef.current);
    }

    autosaveTimerRef.current = window.setTimeout(async () => {
      await speechStorage.saveSpeech(updatedSpeech);
      setIsSaving(false);
    }, 400);
  };

  // Create New Speech
  const handleNewSpeech = async () => {
    const newId = `speech-${Date.now()}`;
    const newSpeech: Speech = {
      id: newId,
      title: 'Novo Discurso',
      category: 'ted',
      targetDurationMinutes: 5,
      targetWpm: settings.defaultWpm || 130,
      tags: ['Rascunho'],
      createdAt: Date.now(),
      updatedAt: Date.now(),
      contentHtml: `
<h2>🎯 <strong>O Gancho de Abertura</strong></h2>
<p>Comece com uma pergunta ou fato inesperado... <span class="stage-cue-badge cue-pause" data-cue-type="pause-2s" contenteditable="false">⏸ 2s Pausa</span></p>
<hr/>
<h2>💡 <strong>Desenvolvimento</strong></h2>
<p>Apresente sua ideia principal com convicção e clareza. <span class="stage-cue-badge cue-emphasis" data-cue-type="emphasis" contenteditable="false">⚡ Ênfase</span></p>
<hr/>
<h2>🚀 <strong>Encerramento</strong></h2>
<p>Deixe uma mensagem final inesquecível. Muito obrigado! <span class="stage-cue-badge cue-applause" data-cue-type="applause" contenteditable="false">👏 Pausa</span></p>
      `.trim(),
      plainText: 'Comece com uma pergunta ou fato inesperado... Apresente sua ideia principal com convicção e clareza. Deixe uma mensagem final inesquecível. Muito obrigado!',
    };

    await speechStorage.saveSpeech(newSpeech);
    setSpeeches((prev) => [newSpeech, ...prev]);
    setActiveSpeech(newSpeech);
  };

  // Delete Speech
  const handleDeleteSpeech = async (id: string) => {
    await speechStorage.deleteSpeech(id);
    const remaining = speeches.filter((s) => s.id !== id);
    setSpeeches(remaining);
    if (activeSpeech?.id === id) {
      setActiveSpeech(remaining[0] || null);
    }
  };

  // Save Settings
  const handleSaveSettings = async (newSettings: AppSettings) => {
    setSettings(newSettings);
    await speechStorage.saveSettings(newSettings);
  };

  // Insert Text from Copilot into Speech
  const handleInsertTextFromCopilot = (textToInsert: string) => {
    if (!activeSpeech) return;
    const formattedAppend = `<p>${textToInsert}</p>`;
    handleSpeechChange({
      contentHtml: activeSpeech.contentHtml + '\n' + formattedAppend,
    });
  };

  if (!activeSpeech) {
    return (
      <div className="app-container" style={{ justifyContent: 'center', alignItems: 'center' }}>
        <p>Carregando Better Talker...</p>
      </div>
    );
  }

  return (
    <div className="app-container">
      {/* Header */}
      <Header
        onOpenSpeechList={() => setSpeechListOpen(true)}
        onNewSpeech={handleNewSpeech}
        onOpenExport={() => setExportOpen(true)}
        onOpenSettings={() => setSettingsOpen(true)}
        onToggleCopilot={() => setIsCopilotOpen(!isCopilotOpen)}
        isCopilotOpen={isCopilotOpen}
        onOpenTeleprompter={() => setTeleprompterOpen(true)}
      />

      {/* Main Content Area */}
      <main className="main-content">
        <RichSpeechEditor
          speech={activeSpeech}
          onChange={handleSpeechChange}
        />

        {/* Copilot Drawer / Sidebar */}
        <CopilotDrawer
          isOpen={isCopilotOpen}
          onClose={() => setIsCopilotOpen(false)}
          speech={activeSpeech}
          metrics={metrics}
          apiKey={settings.geminiApiKey}
          offlineSuggestions={offlineSuggestions}
          onInsertTextIntoSpeech={handleInsertTextFromCopilot}
        />
      </main>

      {/* Real-time Telemetry & Metrics Bar */}
      <SpeechMetricsBar
        metrics={metrics}
        targetMinutes={activeSpeech.targetDurationMinutes}
        wpm={activeSpeech.targetWpm || settings.defaultWpm}
        onWpmChange={(newWpm) => handleSpeechChange({ targetWpm: newWpm })}
        isSaving={isSaving}
      />

      {/* Teleprompter / Rehearsal Mode */}
      <TeleprompterModal
        speech={activeSpeech}
        isOpen={teleprompterOpen}
        onClose={() => setTeleprompterOpen(false)}
        defaultWpm={activeSpeech.targetWpm || settings.defaultWpm}
      />

      {/* Speech Manager Modal */}
      <SpeechListModal
        isOpen={speechListOpen}
        onClose={() => setSpeechListOpen(false)}
        speeches={speeches}
        activeSpeechId={activeSpeech.id}
        onSelectSpeech={(sp) => setActiveSpeech(sp)}
        onNewSpeech={handleNewSpeech}
        onDeleteSpeech={handleDeleteSpeech}
      />

      {/* Settings Modal */}
      <SettingsModal
        isOpen={settingsOpen}
        onClose={() => setSettingsOpen(false)}
        settings={settings}
        onSaveSettings={handleSaveSettings}
      />

      {/* Export & Cue Cards Modal */}
      <ExportModal
        isOpen={exportOpen}
        onClose={() => setExportOpen(false)}
        speech={activeSpeech}
        metrics={metrics}
      />
    </div>
  );
}

export default App;
