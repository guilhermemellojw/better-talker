import { useState, useEffect, useRef, useMemo } from 'react';
import type { Speech, AppSettings, SpeechBlock } from './types/speech';
import { speechStorage } from './services/db';
import {
  calculateSpeechMetrics,
  generateOfflineCopilotSuggestions,
  extractPlainTextFromHtml,
} from './services/rhetoricEngine';
import { parseOutline } from './services/outlineParser';
import { getRelevantEvidence, passagesToContextStrings, candidatesToMeta, type EvidenceMeta } from './copilot/retrieval';
import { buildContextPack } from './copilot/contextPack';
import { applyEditProposal } from './copilot/editProposal';
import { EditHistory } from './copilot/editHistory';
import type { CopilotEditProposal, ProposalApplyStatus } from './copilot/domain';
import { initFirebaseSync, syncSpeechToCloud, syncSettingsToCloud } from './services/syncService';
import { LibraryModal } from './components/Modals/LibraryModal';
import { Header } from './components/Header/Header';
import { BlockEditorTabs } from './components/Editor/BlockEditor';
import { SpeechMetricsBar } from './components/Metrics/SpeechMetricsBar';
import { CopilotDrawer } from './components/Copilot/CopilotDrawer';
import { TeleprompterModal } from './components/Teleprompter/TeleprompterModal';
import { SpeechListModal } from './components/Modals/SpeechListModal';
import { SettingsModal } from './components/Modals/SettingsModal';
import { ExportModal } from './components/Modals/ExportModal';

export function App() {
  const [speeches, setSpeeches] = useState<Speech[]>([]);
  const [activeSpeech, setActiveSpeech] = useState<Speech | null>(null);
  const [activeBlockId, setActiveBlockId] = useState<string>('');
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
  const [libraryOpen, setLibraryOpen] = useState(false);
  const [contextPassages, setContextPassages] = useState<string[]>([]);
  const [evidenceMeta, setEvidenceMeta] = useState<EvidenceMeta[]>([]);
  // Histórico de propostas aceitas do Copilot (Fase 5), um por discurso.
  const [histories] = useState(() => new Map<string, EditHistory>());
  // Re-render para atualizar canUndo/canRedo após push/undo/redo.
  const [, setHistoryTick] = useState(0);

  const historyFor = (speechId: string): EditHistory => {
    let h = histories.get(speechId);
    if (!h) {
      h = new EditHistory();
      histories.set(speechId, h);
      if (histories.size > 10) {
        const oldest = histories.keys().next();
        if (!oldest.done) histories.delete(oldest.value);
      }
    }
    return h;
  };

  const autosaveTimerRef = useRef<number | null>(null);

  useEffect(() => {
    async function initData() {
      await initFirebaseSync();
      const loadedSettings = await speechStorage.getSettings();
      setSettings(loadedSettings);
      const loadedSpeeches = await speechStorage.getAllSpeeches();
      setSpeeches(loadedSpeeches);
      if (loadedSpeeches.length > 0) {
        const first = loadedSpeeches[0];
        setActiveSpeech(first);
        setActiveBlockId(first.blocks[0]?.id || '');
      }
    }
    initData();
  }, []);

  const activeBlock = activeSpeech?.blocks.find((b) => b.id === activeBlockId) || activeSpeech?.blocks[0];

  const metrics = useMemo(() => {
    if (!activeBlock) return calculateSpeechMetrics('', 130, 5);
    return calculateSpeechMetrics(
      activeBlock.contentHtml,
      activeBlock.minutes > 0 ? Math.round(activeSpeech!.targetWpm / activeSpeech!.blocks.length) : settings.defaultWpm || 130,
      activeBlock.minutes
    );
  }, [activeBlock?.contentHtml, activeBlock?.minutes, activeSpeech?.targetWpm, activeSpeech?.blocks, settings.defaultWpm]);

  const offlineSuggestions = useMemo(() => {
    if (!activeBlock) return [];
    const plainText = extractPlainTextFromHtml(activeBlock.contentHtml);
    return generateOfflineCopilotSuggestions(activeSpeech?.title || '', plainText, metrics);
  }, [activeBlock?.contentHtml, metrics, activeSpeech?.title]);

  // Retrieval híbrido com escopo (Fase 3): trechos ranqueados do acervo local.
  // Debounce 600ms para não consultar o Dexie a cada tecla.
  useEffect(() => {
    const plainText = activeBlock?.plainText || '';
    if (!plainText || plainText.trim().length < 20) {
      setContextPassages([]);
      setEvidenceMeta([]);
      return;
    }
    const t = window.setTimeout(async () => {
      const ev = await getRelevantEvidence(plainText, 5);
      const passages = ev.content.map((c) => c.passage);
      setContextPassages(passagesToContextStrings(passages));
      setEvidenceMeta(candidatesToMeta(ev.content, 'content'));
      // ContextPack estruturado disponível para Fase 4/5 ( BE/TH separado ):
      void buildContextPack(
        {
          task: 'research',
          speechTitle: activeSpeech?.title || '',
          speechTimeLimitMinutes: activeSpeech?.targetDurationMinutes,
          blockTitle: activeBlock?.title,
          blockText: plainText,
          blockMinutes: activeBlock?.minutes,
        },
        passages,
      );
    }, 600);
    return () => window.clearTimeout(t);
  }, [activeBlock?.plainText, activeBlock?.title, activeBlock?.minutes, activeSpeech?.title, activeSpeech?.targetDurationMinutes]);

  const handleSpeechChange = (updatedFields: Partial<Speech>) => {
    if (!activeSpeech) return;
    const updatedSpeech: Speech = { ...activeSpeech, ...updatedFields, updatedAt: Date.now() };
    if (updatedFields.contentHtml !== undefined) {
      updatedSpeech.plainText = extractPlainTextFromHtml(updatedFields.contentHtml);
    }
    setActiveSpeech(updatedSpeech);
    setSpeeches((prev) => prev.map((s) => (s.id === updatedSpeech.id ? updatedSpeech : s)));
    setIsSaving(true);
    if (autosaveTimerRef.current) clearTimeout(autosaveTimerRef.current);
    autosaveTimerRef.current = window.setTimeout(async () => {
      await speechStorage.saveSpeech(updatedSpeech);
      await syncSpeechToCloud(updatedSpeech);
      setIsSaving(false);
    }, 400);
  };

  const handleBlockChange = (blockId: string, patch: Partial<SpeechBlock>) => {
    if (!activeSpeech) return;
    const updatedBlocks = activeSpeech.blocks.map((b) =>
      b.id === blockId ? { ...b, ...patch } : b
    );
    handleSpeechChange({ blocks: updatedBlocks });
  };

  const handleNewSpeech = async () => {
    const newId = `speech-${Date.now()}`;
    const blockId = `block-${newId}`;
    const newSpeech: Speech = {
      id: newId,
      title: 'Novo Discurso',
      category: 'ted',
      targetDurationMinutes: 5,
      targetWpm: settings.defaultWpm || 130,
      tags: ['Rascunho'],
      createdAt: Date.now(),
      updatedAt: Date.now(),
      contentHtml: '',
      plainText: '',
      blocks: [
        {
          id: blockId,
          speechId: newId,
          order: 0,
          minutes: 5,
          title: 'Novo Discurso',
          contentHtml: '',
          plainText: '',
        },
      ],
    };
    await speechStorage.saveSpeech(newSpeech);
    setSpeeches((prev) => [newSpeech, ...prev]);
    setActiveSpeech(newSpeech);
    setActiveBlockId(blockId);
  };

  const handleImportOutline = async (buffer: ArrayBuffer) => {
    const { speech, blocks } = parseOutline(buffer);
    const newId = `speech-${Date.now()}`;
    const blockIds = blocks.map((_, i) => `block-${newId}-${i}`);
    const fullBlocks = blocks.map((b, i) => ({
      ...b,
      id: blockIds[i],
      speechId: newId,
    }));
    const totalMinutes = fullBlocks.reduce((acc, b) => acc + b.minutes, 0);
    const newSpeech: Speech = {
      id: newId,
      title: speech.title || 'Discurso Importado',
      contentHtml: '',
      plainText: '',
      targetDurationMinutes: totalMinutes,
      targetWpm: settings.defaultWpm || 130,
      category: 'geral',
      tags: ['Importado'],
      createdAt: Date.now(),
      updatedAt: Date.now(),
      blocks: fullBlocks,
    };
    await speechStorage.saveSpeech(newSpeech);
    setSpeeches((prev) => [newSpeech, ...prev]);
    setActiveSpeech(newSpeech);
    setActiveBlockId(blockIds[0]);
  };

  const handleDeleteSpeech = async (id: string) => {
    await speechStorage.deleteSpeech(id);
    histories.delete(id);
    const remaining = speeches.filter((s) => s.id !== id);
    setSpeeches(remaining);
    if (activeSpeech?.id === id) {
      setActiveSpeech(remaining[0] || null);
      setActiveBlockId(remaining[0]?.blocks[0]?.id || '');
    }
  };

  const handleSaveSettings = async (newSettings: AppSettings) => {
    setSettings(newSettings);
    await speechStorage.saveSettings(newSettings);
    await syncSettingsToCloud(newSettings);
  };

  const handleInsertTextFromCopilot = (textToInsert: string) => {
    if (!activeBlock) return;
    const formattedAppend = `<p>${textToInsert}</p>`;
    const updatedContent = activeBlock.contentHtml + formattedAppend;
    handleBlockChange(activeBlock.id, { contentHtml: updatedContent });
  };

  // Fase 5: aceitar proposta do Copilot — valida, aplica atomicamente e registra.
  const handleAcceptProposal = (proposal: CopilotEditProposal): ProposalApplyStatus => {
    if (!activeSpeech) return 'invalid';
    const result = applyEditProposal(activeSpeech, proposal);
    if (result.status !== 'applied' || !result.speech) return result.status;
    historyFor(activeSpeech.id).push(activeSpeech.blocks);
    setHistoryTick((t) => t + 1);
    handleSpeechChange({ blocks: result.speech.blocks });
    return 'applied';
  };

  const handleUndo = () => {
    if (!activeSpeech) return;
    const prev = historyFor(activeSpeech.id).undo(activeSpeech.blocks);
    if (!prev) return;
    setHistoryTick((t) => t + 1);
    handleSpeechChange({ blocks: prev });
  };

  const handleRedo = () => {
    if (!activeSpeech) return;
    const next = historyFor(activeSpeech.id).redo(activeSpeech.blocks);
    if (!next) return;
    setHistoryTick((t) => t + 1);
    handleSpeechChange({ blocks: next });
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
      <Header
        onOpenSpeechList={() => setSpeechListOpen(true)}
        onNewSpeech={handleNewSpeech}
        onOpenExport={() => setExportOpen(true)}
        onOpenSettings={() => setSettingsOpen(true)}
        onToggleCopilot={() => setIsCopilotOpen(!isCopilotOpen)}
        isCopilotOpen={isCopilotOpen}
        onOpenTeleprompter={() => setTeleprompterOpen(true)}
        onOpenLibrary={() => setLibraryOpen(true)}
        onUndo={handleUndo}
        onRedo={handleRedo}
        canUndo={histories.get(activeSpeech.id)?.canUndo ?? false}
        canRedo={histories.get(activeSpeech.id)?.canRedo ?? false}
      />

      <main className="main-content">
        <BlockEditorTabs
          speech={activeSpeech}
          activeBlockId={activeBlockId}
          onActiveBlockChange={setActiveBlockId}
          onBlockChange={handleBlockChange}
          onSpeechChange={handleSpeechChange}
        />

        <CopilotDrawer
          isOpen={isCopilotOpen}
          onClose={() => setIsCopilotOpen(false)}
          speech={activeSpeech}
          metrics={metrics}
          apiKey={settings.geminiApiKey}
          offlineSuggestions={offlineSuggestions}
          activeBlock={activeBlock}
          onInsertTextIntoSpeech={handleInsertTextFromCopilot}
          onAcceptProposal={handleAcceptProposal}
          contextPassages={contextPassages}
          evidenceMeta={evidenceMeta}
        />
      </main>

      <SpeechMetricsBar
        metrics={metrics}
        targetMinutes={activeBlock?.minutes || activeSpeech.targetDurationMinutes}
        wpm={activeSpeech.targetWpm || settings.defaultWpm}
        onWpmChange={(newWpm) => handleSpeechChange({ targetWpm: newWpm })}
        isSaving={isSaving}
      />

      <TeleprompterModal
        speech={activeSpeech}
        isOpen={teleprompterOpen}
        onClose={() => setTeleprompterOpen(false)}
        defaultWpm={activeSpeech.targetWpm || settings.defaultWpm}
      />

      <SpeechListModal
        isOpen={speechListOpen}
        onClose={() => setSpeechListOpen(false)}
        speeches={speeches}
        activeSpeechId={activeSpeech.id}
        onSelectSpeech={(sp) => {
          setActiveSpeech(sp);
          setActiveBlockId(sp.blocks[0]?.id || '');
          setSpeechListOpen(false);
        }}
        onNewSpeech={handleNewSpeech}
        onDeleteSpeech={handleDeleteSpeech}
        onImportOutline={handleImportOutline}
      />

      <SettingsModal
        isOpen={settingsOpen}
        onClose={() => setSettingsOpen(false)}
        settings={settings}
        onSaveSettings={handleSaveSettings}
      />

      <ExportModal
        isOpen={exportOpen}
        onClose={() => setExportOpen(false)}
        speech={activeSpeech}
        metrics={metrics}
      />

      <LibraryModal
        isOpen={libraryOpen}
        onClose={() => setLibraryOpen(false)}
        onImportComplete={() => {}}
      />
    </div>
  );
}

export default App;
