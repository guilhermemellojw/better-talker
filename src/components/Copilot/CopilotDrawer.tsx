import { useEffect, useRef, useState } from 'react';
import type { Speech, SpeechMetrics, CopilotSuggestion, SpeechBlock } from '../../types/speech';
import { createCopilotProviderFromEnv } from '../../copilot/providerFactory';
import { isProviderError, type ProviderErrorCode } from '../../copilot/llmErrors';
import type { LlmResponseMeta } from '../../copilot/llmProvider';
import type { ContextPack, CopilotEditProposal, EditProposalMode, ProposalApplyStatus, TextVerification, TrainingCategory } from '../../copilot/domain';
import { stripHtmlToText, hashText } from '../../copilot/editProposal';
import { parseEditProposal } from '../../copilot/proposalParser';
import { verifyText } from '../../copilot/verifier';
import { buildScopeFromLibrary, dexiePassageStore } from '../../copilot/retrieval';
import { retrieveTraining } from '../../copilot/trainingRetriever';
import { trainingCategoryForAction, trainingCategoryForEditMode } from '../../copilot/trainingIntent';
import { buildContextPackFromCandidates, type BuildInput } from '../../copilot/contextPack';
import type { EvidenceMeta } from '../../copilot/retrieval';
import { analyzeSpeech, speechContentHash } from '../../copilot/speechAnalyzer';
import type { SpeechAnalysis } from '../../copilot/speechAnalysis';
import {
  inferIntent,
  validateOutgoingMessage,
  friendlyChatError,
  OFFLINE_CHAT_NOTICE,
  FOLLOW_UP_SUGGESTIONS,
  QUICK_ACTIONS,
  contextLabel,
  type ChatMessage,
} from '../../copilot/chatEngine';
import { Send, RotateCcw, Bot, User } from 'lucide-react';
import { Sparkles, X, Wand2, Copy, Check, PlusCircle, Square } from 'lucide-react';

const EDIT_MODES: Array<{ id: EditProposalMode; label: string; desc: string }> = [
  { id: 'suggest', label: 'Sugerir', desc: 'Só texto, sem tocar no discurso' },
  { id: 'rewrite', label: 'Reescrever', desc: 'Nova versão integral do bloco' },
  { id: 'improve', label: 'Melhorar', desc: 'Clareza preservando as ideias' },
  { id: 'insert', label: 'Inserir', desc: 'Conteúdo novo após o bloco' },
  { id: 'delete', label: 'Excluir', desc: 'Propor remoção do bloco' },
];

interface CopilotDrawerProps {
  isOpen: boolean;
  onClose: () => void;
  speech: Speech;
  metrics: SpeechMetrics;
  apiKey: string;
  offlineSuggestions: CopilotSuggestion[];
  activeBlock?: SpeechBlock;
  onInsertTextIntoSpeech: (text: string) => void;
  onAcceptProposal: (proposal: CopilotEditProposal) => ProposalApplyStatus | Promise<ProposalApplyStatus>;
  onSelectBlock?: (blockId: string) => void;
  contextPassages?: string[];
  evidenceMeta?: EvidenceMeta[];
  selectedText?: string;
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
  onAcceptProposal,
  onSelectBlock,
  contextPassages = [],
  evidenceMeta = [],
  selectedText = '',
}: CopilotDrawerProps) => {
  const [activeTone, setActiveTone] = useState<'ted' | 'pitch' | 'motivational' | 'academic' | 'humorous'>('ted');
  const [isLoading, setIsLoading] = useState(false);
  const [resultText, setResultText] = useState<string | null>(null);
  const [resultTitle, setResultTitle] = useState<string>('');
  const [copied, setCopied] = useState(false);
  const [resultMeta, setResultMeta] = useState<LlmResponseMeta | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  // Fase 5: edição assistida — o modelo propõe, o usuário decide.
  const [editMode, setEditMode] = useState<EditProposalMode>('suggest');
  const [proposal, setProposal] = useState<CopilotEditProposal | null>(null);
  const [proposalNotice, setProposalNotice] = useState<string | null>(null);
  // Fase 6: verificação sob demanda (local-first, sem juiz remoto por padrão).
  const [verifying, setVerifying] = useState(false);
  const [verification, setVerification] = useState<TextVerification | null>(null);
  const [verifiedHash, setVerifiedHash] = useState<string | null>(null);
  const [proposalVerification, setProposalVerification] = useState<TextVerification | null>(null);
  // Fase 12: feedback visível quando verificação/análise falham (nunca silêncio).
  const [verifyNotice, setVerifyNotice] = useState<string | null>(null);
  const [analysisNotice, setAnalysisNotice] = useState<string | null>(null);
  // Fase 9: análise estrutural local (sem nota global, sem juízo absoluto).
  const [analyzing, setAnalyzing] = useState(false);
  const [analysis, setAnalysis] = useState<SpeechAnalysis | null>(null);
  // Fase 15: thread de conversa (§19: memória só para a sessão de uso atual).
  const [chatMessages, setChatMessages] = useState<ChatMessage[]>([]);
  const [chatDraft, setChatDraft] = useState('');
  // Fase 15: pendência e erro separados — erro é dispensável (§30) e nunca
  // deixa o composer travado; pendência bloqueia novo envio (§20).
  const [chatPending, setChatPending] = useState(false);
  const [chatError, setChatError] = useState<string | null>(null);
  const [isOffline, setIsOffline] = useState(
    typeof navigator !== 'undefined' && navigator.onLine === false,
  );
  const threadEndRef = useRef<HTMLDivElement | null>(null);
  const threadViewportRef = useRef<HTMLDivElement | null>(null);
  const stickToBottomRef = useRef(true);
  const chatIdRef = useRef(0);
  const chatAbortRef = useRef<AbortController | null>(null);
  const hasApiKey = Boolean(apiKey && apiKey.trim().length > 0);

  // Online/offline reativo (§30, §47): sem polling, sem rede extra.
  useEffect(() => {
    const goOnline = () => setIsOffline(false);
    const goOffline = () => setIsOffline(true);
    window.addEventListener('online', goOnline);
    window.addEventListener('offline', goOffline);
    return () => {
      window.removeEventListener('online', goOnline);
      window.removeEventListener('offline', goOffline);
    };
  }, []);

  const friendlyError = (code: ProviderErrorCode): string => {
    switch (code) {
      case 'cancelled':
        return 'Geração cancelada. Nenhuma alteração foi feita no discurso.';
      case 'timeout':
        return 'Tempo esgotado ao chamar o modelo. Tente novamente ou use o motor offline.';
      case 'authentication':
        return 'Chave de API inválida ou sem autorização. Confira nas Configurações.';
      case 'rate_limit':
        return 'Limite de requisições atingido. Aguarde um pouco e tente de novo.';
      case 'network':
        return 'Sem conexão com o provedor. O motor offline continua disponível abaixo.';
      case 'unavailable':
        return 'Modelo remoto indisponível no momento. Tente mais tarde.';
      default:
        return 'Ocorreu um erro ao processar com a IA. Tente novamente.';
    }
  };

  const handleCancel = () => {
    abortRef.current?.abort();
  };

  const handleCancelChat = () => {
    chatAbortRef.current?.abort();
  };

  /**
   * Fase 15: caminho único do chat livre (§4) — quick actions e sugestões
   * passam por aqui com a mesma lógica de prompt (§40.15).
   * Fluxo: mensagem → intent → training → ContextPack → provider → thread.
   */
  const sendChatMessage = async (rawMessage: string) => {
    const validated = validateOutgoingMessage(rawMessage);
    if (!validated.ok) return;
    if (chatPending) return; // uma geração por vez (§20)
    const message = validated.text;
    const history = chatMessages;
    const isFirstMessage = history.length === 0;
    const intent = inferIntent(message, isFirstMessage);
    setChatDraft('');
    setChatError(null);
    setChatPending(true);
    setIsLoading(true);
    setChatMessages((prev) => [
      ...prev,
      {
        id: `chat-u-${++chatIdRef.current}`,
        role: 'user',
        text: message,
        blockTitle: activeBlock?.title,
        createdAt: Date.now(),
      },
    ]);
    const ctrl = new AbortController();
    chatAbortRef.current = ctrl;
    try {
      const pack = await fetchTrainingPack(
        activeBlock?.plainText || activeBlock?.title || message,
        intent.trainingCategory,
      );
      const provider = createCopilotProviderFromEnv(apiKey);
      const res = await provider.generate({
        action: 'chat',
        text: activeBlock?.plainText || activeBlock?.title || speech.title,
        tone: activeTone,
        contextPassages,
        contextPack: pack,
        blockTitle: activeBlock?.title,
        blockMinutes: activeBlock?.minutes,
        chat: {
          message,
          history,
          isFirstMessage,
        },
        signal: ctrl.signal,
      });
      setChatMessages((prev) => [
        ...prev,
        {
          id: `chat-a-${chatIdRef.current}`,
          role: 'assistant',
          text: res.text,
          createdAt: Date.now(),
        },
      ]);
      setChatError(null);
    } catch (err) {
      console.error(err);
      // §30: erro humano no próprio fio da conversa; descartável e nunca trava o envio.
      setChatError(
        isProviderError(err) ? friendlyChatError(err.code) : 'Não consegui gerar a resposta agora. Tente novamente.',
      );
    } finally {
      if (chatAbortRef.current === ctrl) {
        chatAbortRef.current = null;
      }
      setChatPending(false);
      setIsLoading(false);
    }
  };

  // Fase 15 (§21): auto-scroll só quando o usuário já está no fim da conversa.
  useEffect(() => {
    const vp = threadViewportRef.current;
    if (!vp) return;
    const onScroll = () => {
      stickToBottomRef.current = vp.scrollHeight - vp.scrollTop - vp.clientHeight < 80;
    };
    vp.addEventListener('scroll', onScroll, { passive: true });
    return () => vp.removeEventListener('scroll', onScroll);
  }, []);
  useEffect(() => {
    if (!stickToBottomRef.current) return;
    threadEndRef.current?.scrollIntoView({ block: 'end' });
  }, [chatMessages.length, chatPending, chatError, isLoading]);

  /**
   * Fase 7 (§13): busca training pela intenção e monta ContextPack com trilhos
   * separados. Retorna undefined quando a intenção pede só conteúdo.
   */
  const fetchTrainingPack = async (
    queryText: string,
    category: TrainingCategory | null,
  ): Promise<ContextPack | undefined> => {
    if (!category) return undefined;
    try {
      const scope = await buildScopeFromLibrary();
      const res = await retrieveTraining({
        query: queryText,
        category,
        scope,
        store: dexiePassageStore,
        limit: 3,
      });
      if (res.status !== 'ok' || res.hits.length === 0) return undefined;
      const input: BuildInput = {
        task: 'research',
        speechTitle: speech.title,
        blockTitle: activeBlock?.title,
        blockText: queryText,
        blockMinutes: activeBlock?.minutes,
      };
      return buildContextPackFromCandidates(input, [], res.hits);
    } catch (err) {
      console.warn('Training retrieval indisponível, seguindo só com conteúdo:', err);
      return undefined;
    }
  };

  /** Fase 5: gera proposta estruturada (alvo do app, não do modelo). */
  const handleGenerateProposal = async (
    brief?: string,
    targetOverride?: SpeechBlock,
    modeOverride?: EditProposalMode,
  ) => {
    const target = targetOverride ?? activeBlock;
    const mode = modeOverride ?? editMode;
    if (!target) {
      setProposalNotice('Preciso saber qual parte do discurso você quer alterar.');
      return;
    }
    abortRef.current?.abort();
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    setIsLoading(true);
    setProposal(null);
    setProposalNotice(null);
    setProposalVerification(null);
    setResultText(null);
    try {
      if (mode === 'delete') {
        // Exclusão é determinística e local: sem LLM.
        const parsed = parseEditProposal('', speech, target.id, 'delete');
        if (!parsed.ok) throw new Error('invalid');
        setProposal(parsed.proposal);
        return;
      }
      if (mode === 'suggest') return; // Sugerir usa o fluxo de texto acima.
      const provider = createCopilotProviderFromEnv(apiKey);
      const trainingPack = await fetchTrainingPack(
        target.plainText || target.title,
        trainingCategoryForEditMode(mode),
      );
      const res = await provider.generate({
        text: target.plainText || target.title,
        // action é irrelevante aqui: responseFormat + editMode dirigem o prompt.
        action: 'rewrite',
        tone: activeTone,
        contextPassages,
        contextPack: trainingPack,
        blockTitle: target.title,
        blockMinutes: target.minutes,
        responseFormat: 'edit-proposal',
        editMode: mode,
        brief,
        signal: ctrl.signal,
      });
      const parsed = parseEditProposal(res.text, speech, target.id, mode);
      if (!parsed.ok) {
        setProposalNotice('A resposta do modelo veio em formato inválido. Tente gerar novamente.');
        return;
      }
      setProposal(parsed.proposal);
      setResultMeta(res.meta);
    } catch (err) {
      console.error(err);
      setProposalNotice(isProviderError(err) ? friendlyError(err.code) : 'Ocorreu um erro ao gerar a proposta. Tente novamente.');
    } finally {
      if (abortRef.current === ctrl) abortRef.current = null;
      setIsLoading(false);
    }
  };

  const handleAcceptProposal = async () => {
    if (!proposal) return;
    const status = await onAcceptProposal(proposal);
    if (status === 'applied') {
      setProposal(null);
      setProposalNotice('Proposta aplicada. Use Desfazer no topo se precisar reverter.');
    } else if (status === 'stale_proposal') {
      setProposalNotice('Proposta obsoleta: o bloco mudou depois da geração. Gere novamente.');
    } else {
      setProposalNotice('Proposta inválida para o estado atual. Gere novamente.');
    }
  };

  const handleRejectProposal = () => {
    // Rejeitar não altera nada nem registra histórico.
    setProposal(null);
    setProposalNotice(null);
    setProposalVerification(null);
  };

  const STATUS_GLYPH: Record<string, string> = {
    supported: '✓',
    partially_supported: '⚠',
    insufficient: '?',
    creative: '💡',
  };

  /** Fase 6 (§23): verificação sob demanda do bloco ativo. */
  const handleVerifyBlock = async () => {
    const text = activeBlock?.plainText || '';
    if (!text.trim()) return;
    setVerifying(true);
    setVerifyNotice(null);
    try {
      const scope = await buildScopeFromLibrary();
      const result = await verifyText({
        text,
        blockId: activeBlock?.id,
        scope,
        store: dexiePassageStore,
      });
      setVerification(result);
      setVerifiedHash(hashText(text));
    } catch (err) {
      console.error(err);
      setVerifyNotice('Não foi possível verificar agora (acervo indisponível). Tente novamente.');
    } finally {
      setVerifying(false);
    }
  };

  /** Fase 6 (§22): verifica o conteúdo proposto antes do aceite (não aplica). */
  const handleVerifyProposal = async () => {
    if (!proposal) return;
    setVerifying(true);
    try {
      const scope = await buildScopeFromLibrary();
      const proposedText = proposal.operations
        .map((op) => (op.type === 'delete' ? '' : stripHtmlToText(op.contentHtml)))
        .filter(Boolean)
        .join('\n');
      const result = await verifyText({
        text: proposedText || '(remoção — sem conteúdo novo)',
        blockId: proposal.operations[0]?.targetId,
        scope,
        store: dexiePassageStore,
      });
      setProposalVerification(result);
    } catch (err) {
      console.error(err);
      setProposalNotice('Não foi possível verificar agora (acervo indisponível). Tente novamente.');
    } finally {
      setVerifying(false);
    }
  };

  const verificationStale =
    verification !== null &&
    verifiedHash !== null &&
    hashText(activeBlock?.plainText || '') !== verifiedHash;

  /** Fase 9: análise estrutural local do discurso inteiro (sem LLM). */
  const handleAnalyze = () => {
    setAnalyzing(true);
    setAnalysisNotice(null);
    try {
      setAnalysis(analyzeSpeech(speech, { wpm: speech.targetWpm || 130 }));
    } catch (err) {
      console.error(err);
      setAnalysisNotice('Não foi possível analisar agora. Tente novamente.');
    } finally {
      setAnalyzing(false);
    }
  };

  const analysisStale =
    analysis !== null && analysis.contentHash !== speechContentHash(speech);

  /** Fase 9 (§20): sugestão da observação vira proposta Fase 5 (nada automático). */
  const handleObservationSuggest = (
    observation: { suggestion?: string; blockIds: string[]; type: string },
  ) => {
    if (!observation.suggestion) return;
    const target =
      speech.blocks.find((b) => b.id === observation.blockIds[0]) ?? activeBlock;
    if (!target) return;
    const mode = observation.type === 'TRANSITION' ? 'insert' as const : 'improve' as const;
    setEditMode(mode);
    void handleGenerateProposal(observation.suggestion, target, mode);
  };

  const blockTitleOf = (blockId: string): string =>
    speech.blocks.find((b) => b.id === blockId)?.title ?? blockId;

  const handleRunAIAction = async (
    action: 'hook' | 'rewrite' | 'critique' | 'cues' | 'shorten',
    title: string
  ) => {
    abortRef.current?.abort();
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    setIsLoading(true);
    setResultTitle(title);
    setResultText(null);
    setResultMeta(null);
    const textToAnalyze = activeBlock?.plainText || speech.plainText || speech.title;

    try {
      // A UI depende da abstração; a factory decide Gemini/Qwen (§3).
      const provider = createCopilotProviderFromEnv(apiKey);
      const trainingPack = await fetchTrainingPack(textToAnalyze, trainingCategoryForAction(action));
      const res = await provider.generate({
        text: textToAnalyze,
        action,
        tone: activeTone,
        contextPassages,
        contextPack: trainingPack,
        blockTitle: activeBlock?.title,
        blockMinutes: activeBlock?.minutes,
        signal: ctrl.signal,
      });
      setResultText(res.text);
      setResultMeta(res.meta);
    } catch (err) {
      console.error(err);
      setResultText(isProviderError(err) ? friendlyError(err.code) : 'Ocorreu um erro ao processar com a IA. Tente novamente.');
    } finally {
      if (abortRef.current === ctrl) abortRef.current = null;
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
            {/* F17: contexto automático — bloco ou trecho selecionado */}
            <span style={{ fontSize: '0.72rem', color: 'var(--primary)' }}>
              {selectedText
                ? contextLabel(undefined, undefined, selectedText)
                : activeBlock
                  ? contextLabel(activeBlock.title)
                  : ''}
            </span>
          </div>
          <span style={{ fontSize: '0.72rem', color: 'var(--text-tertiary)' }}>
            {metrics.wordCount} palavras • {metrics.formattedEstimatedTime}
          </span>
          <span className={`copilot-mode-badge ${hasApiKey ? 'gemini' : 'offline'}`}>
            {hasApiKey ? '✨ Gemini AI' : '⚡ Motor Offline'}
          </span>
        </div>
        <button type="button" className="modal-close-btn" onClick={onClose} title="Fechar painel">
          <X size={18} />
        </button>
      </div>

      <div className="copilot-body">
        {/* Fase 15: a conversa é o caminho principal (§4–§10) */}
        <div className="copilot-chat" data-testid="copilot-chat">
          {chatMessages.length === 0 && !chatPending && !isLoading ? (
            <div className="chat-empty-state">
              <div className="chat-empty-title">Como posso ajudar com este discurso?</div>
              <div className="chat-empty-hint">Você pode escrever naturalmente, por exemplo:</div>
              <ul className="chat-empty-examples">
                <li>“Quero melhorar essa introdução.”</li>
                <li>“Essa explicação está complicada.”</li>
                <li>“Crie uma ilustração para esse ponto.”</li>
                <li>“Como posso deixar isso mais natural?”</li>
              </ul>
            </div>
          ) : (
            <div className="chat-thread" ref={threadViewportRef}>
              {chatMessages.map((m, idx) => (
                <div key={m.id} className={`chat-message chat-${m.role}`} aria-label={m.role === 'user' ? 'Sua mensagem' : 'Resposta do Copilot'}>
                  <div className="chat-message-icon" aria-hidden="true">
                    {m.role === 'user' ? <User size={14} /> : <Bot size={14} />}
                  </div>
                  <div className="chat-message-bubble">
                    <div className="chat-message-text">{m.text}</div>
                    {m.role === 'assistant' && (
                      <div className="chat-message-actions">
                        {(() => {
                          // Fase 15 (§24/§52): a sugestão conversacional vira proposta
                          // F5 pelo caminho existente — o pedido do usuário é o brief.
                          const prev = idx > 0 ? chatMessages[idx - 1] : undefined;
                          const briefFromChat = prev && prev.role === 'user' ? prev.text : '';
                          if (!briefFromChat) return null;
                          return (
                            <button
                              type="button"
                              className="chat-chip"
                              title="Transforma a sugestão em proposta de edição (preview antes de aplicar)"
                              disabled={chatPending || isLoading}
                              onClick={() => void handleGenerateProposal(briefFromChat, activeBlock, 'improve')}
                            >
                              <PlusCircle size={12} />
                              <span>Criar proposta</span>
                            </button>
                          );
                        })()}
                        {FOLLOW_UP_SUGGESTIONS.map((label) => (
                          <button
                            key={label}
                            type="button"
                            className="chat-chip"
                            disabled={chatPending}
                            onClick={() => void sendChatMessage(`${label}.`)}
                          >
                            {label}
                          </button>
                        ))}
                        <button
                          type="button"
                          className="chat-chip"
                          title="Gera outra resposta com o mesmo contexto"
                          disabled={chatPending}
                          onClick={() => void sendChatMessage('Gere outra versão da resposta anterior.')}
                        >
                          <RotateCcw size={12} />
                          <span>Outra versão</span>
                        </button>
                      </div>
                    )}
                  </div>
                </div>
              ))}
              {chatPending && (
                <div className="chat-message chat-assistant" aria-live="polite">
                  <div className="chat-message-icon" aria-hidden="true"><Bot size={14} /></div>
                  <div className="chat-message-bubble">
                    <div className="chat-message-text">
                      Gerando...
                      <button type="button" className="chat-chip" onClick={handleCancelChat} style={{ marginLeft: '0.5rem' }}>
                        <Square size={12} />
                        <span>Cancelar</span>
                      </button>
                    </div>
                  </div>
                </div>
              )}
              {chatError && (
                <div className="chat-message chat-assistant" aria-live="polite">
                  <div className="chat-message-icon" aria-hidden="true"><Bot size={14} /></div>
                  <div className="chat-message-bubble">
                    <div className="chat-message-text chat-error-text">{chatError}</div>
                    <button type="button" className="chat-chip" onClick={() => setChatError(null)}>
                      Fechar
                    </button>
                  </div>
                </div>
              )}
              <div ref={threadEndRef} />
            </div>
          )}
        </div>
        {(() => {
          const label = contextLabel(activeBlock?.title, speech.title);
          return label ? (
            <div className="chat-context-label" title="Parte do discurso considerada na conversa">
              {label}
            </div>
          ) : null;
        })()}
        {evidenceMeta.length > 0 && (
          <details className="chat-provenance">
            <summary>
              Fontes e apoio ▸
              <span style={{ color: 'var(--text-tertiary)' }}>
                {' '}{evidenceMeta.filter((m) => m.track === 'content').length} conteúdo · {evidenceMeta.filter((m) => m.track === 'training').length} técnica
              </span>
            </summary>
            {evidenceMeta.slice(0, 5).map((m, i) => (
              <div key={i} className="chat-provenance-item" title={m.track === 'training' ? 'Técnica de apresentação (BE/TH) — não é prova factual' : 'Relevância de recuperação — não é certeza factual'}>
                {m.track === 'training' ? '🎤' : '📖'} {m.reference} · Relevância {m.relevance.toFixed(2)}
                {m.category && m.category !== 'unknown' ? ` · técnica: ${m.category}` : ''}
              </div>
            ))}
          </details>
        )}
        {isOffline && (
          <div className="chat-offline-notice" aria-live="polite">
            {OFFLINE_CHAT_NOTICE}
          </div>
        )}
        {/* Verificação de fidelidade (Fase 6, sob demanda) */}
        <div className="copilot-section-verify" style={{ marginBottom: '0.5rem' }}>
          <button
            type="button"
            className="ai-action-btn"
            onClick={handleVerifyBlock}
            disabled={verifying || !(activeBlock?.plainText || '').trim()}
            title="Extrai afirmações do bloco e busca suporte nas fontes autorizadas"
            style={{ width: '100%' }}
          >
            <span className="btn-icon">🔍</span>
            <span className="btn-title">{verifying ? 'Verificando...' : 'Verificar fidelidade'}</span>
            <span className="btn-desc">Suporte nas fontes · sem juízo absoluto</span>
          </button>
          {verifyNotice && (
            <div style={{ fontSize: '0.78rem', color: 'var(--text-tertiary)', marginTop: '0.5rem' }}>
              {verifyNotice}
            </div>
          )}
          {verification && (
            <div className="ai-result-box" style={{ marginTop: '0.5rem' }}>
              <div style={{ fontSize: '0.8rem', fontWeight: 700 }}>
                ✓ {verification.summary.supported} com suporte · ⚠ {verification.summary.partial} parcial ·{' '}
                ? {verification.summary.insufficient} sem suporte · 💡 {verification.summary.creative} criativos
              </div>
              {verification.limited && (
                <div style={{ fontSize: '0.7rem', color: 'var(--text-tertiary)' }}>
                  Verificação local — sem avaliador remoto disponível.
                </div>
              )}
              {verificationStale && (
                <div style={{ fontSize: '0.72rem', color: 'var(--primary)' }}>
                  Verificação obsoleta: o bloco mudou. Verifique novamente.
                </div>
              )}
              {!verificationStale && verification.claims.map((vc) => (
                <details key={vc.claim.id} style={{ fontSize: '0.78rem', marginTop: '0.4rem' }}>
                  <summary>
                    {STATUS_GLYPH[vc.status]} “{vc.claim.text.slice(0, 80)}{vc.claim.text.length > 80 ? '…' : ''}”
                    <span style={{ color: 'var(--text-tertiary)' }}> ({vc.claim.type})</span>
                  </summary>
                  <div style={{ marginTop: '0.25rem' }}>{vc.reason}</div>
                  {vc.evidence.map((e) => (
                    <div key={e.evidenceId} style={{ color: 'var(--text-tertiary)', marginTop: '0.2rem' }}>
                      📖 {e.provenance.ref || e.provenance.title || e.evidenceId}
                      {e.score != null ? ` · relevância ${e.score.toFixed(2)}` : ''}
                    </div>
                  ))}
                </details>
              ))}
            </div>
          )}
        </div>
        {/* Análise do discurso (Fase 9, local) */}
        <div className="copilot-section-analysis" style={{ marginBottom: '0.5rem' }}>
          <button
            type="button"
            className="ai-action-btn"
            onClick={handleAnalyze}
            disabled={analyzing || speech.blocks.length === 0}
            title="Analisa estrutura, equilíbrio, repetição, clareza e tempo — tudo local"
            style={{ width: '100%' }}
          >
            <span className="btn-icon">🧭</span>
            <span className="btn-title">{analyzing ? 'Analisando...' : 'Análise do discurso'}</span>
            <span className="btn-desc">Estrutura e tempo · sem nota global</span>
          </button>
          {analysisNotice && (
            <div style={{ fontSize: '0.78rem', color: 'var(--text-tertiary)', marginTop: '0.5rem' }}>
              {analysisNotice}
            </div>
          )}
          {analysis && (
            <div className="ai-result-box" style={{ marginTop: '0.5rem' }}>
              <div style={{ fontSize: '0.8rem', fontWeight: 700 }}>
                {analysis.observations.filter((o) => o.severity === 'INFO').length} ✓ ·{' '}
                {analysis.observations.filter((o) => o.severity === 'ATTENTION').length} ⚠ ·{' '}
                {analysis.observations.filter((o) => o.severity === 'SUGGESTION').length} 💡
              </div>
              <div style={{ fontSize: '0.78rem', marginTop: '0.25rem' }}>
                Tempo estimado: {analysis.estimatedTime.formattedTotal} (ritmo {analysis.estimatedTime.wpm} ppm)
              </div>
              <div style={{ fontSize: '0.7rem', color: 'var(--text-tertiary)' }}>
                {analysis.estimatedTime.disclaimer}
              </div>
              {analysisStale && (
                <div style={{ fontSize: '0.72rem', color: 'var(--primary)' }}>
                  Análise obsoleta: o discurso mudou. Analise novamente.
                </div>
              )}
              {!analysisStale && analysis.observations.map((o) => {
                const glyph = o.severity === 'ATTENTION' ? '⚠' : o.severity === 'SUGGESTION' ? '💡' : '✓';
                return (
                  <details key={o.id} style={{ fontSize: '0.78rem', marginTop: '0.4rem' }}>
                    <summary>
                      {glyph} {o.message}
                    </summary>
                    <div style={{ marginTop: '0.25rem' }}>Por quê: {o.reason}</div>
                    {o.blockIds.length > 0 && onSelectBlock && (
                      <div style={{ marginTop: '0.25rem' }}>
                        {o.blockIds.map((id) => (
                          <button
                            key={id}
                            type="button"
                            className="action-btn-sm"
                            onClick={() => onSelectBlock(id)}
                            title="Ir para o bloco"
                            style={{ marginRight: '0.25rem' }}
                          >
                            <span>→ {blockTitleOf(id).slice(0, 30)}</span>
                          </button>
                        ))}
                      </div>
                    )}
                    {o.suggestion && (
                      <div style={{ marginTop: '0.25rem' }}>
                        <div>Sugestão: {o.suggestion}</div>
                        <button
                          type="button"
                          className="action-btn-sm primary"
                          onClick={() => handleObservationSuggest(o)}
                          disabled={isLoading}
                          title="Gera proposta de edição (preview antes de aplicar)"
                          style={{ marginTop: '0.25rem' }}
                        >
                          <PlusCircle size={14} />
                          <span>Gerar sugestão</span>
                        </button>
                      </div>
                    )}
                  </details>
                );
              })}
            </div>
          )}
        </div>
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
        <div className="copilot-section-actions">
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

        {/* Edição assistida (Fase 5): o modelo propõe, o usuário decide */}
        <div className="copilot-section-edit">
          <span className="tone-picker-label" style={{ display: 'block', marginBottom: '0.4rem' }}>
            Edição assistida — alvo: {activeBlock?.title || 'nenhum bloco'}
          </span>
          <div className="tone-pills" style={{ marginBottom: '0.5rem' }}>
            {EDIT_MODES.map((m) => (
              <button
                key={m.id}
                type="button"
                className={`tone-pill-btn ${editMode === m.id ? 'active' : ''}`}
                onClick={() => {
                  setEditMode(m.id);
                  setProposal(null);
                  setProposalNotice(null);
                }}
                title={m.desc}
              >
                {m.label}
              </button>
            ))}
          </div>
          {editMode !== 'suggest' && (
            <button
              type="button"
              className="ai-action-btn"
              onClick={() => void handleGenerateProposal()}
              disabled={isLoading || !activeBlock}
              title={EDIT_MODES.find((m) => m.id === editMode)?.desc}
              style={{ width: '100%', marginBottom: '0.5rem' }}
            >
              <span className="btn-icon">✏️</span>
              <span className="btn-title">Gerar proposta ({EDIT_MODES.find((m) => m.id === editMode)?.label})</span>
              <span className="btn-desc">Preview antes de aplicar — nada muda sozinho</span>
            </button>
          )}
          {proposalNotice && (
            <div style={{ fontSize: '0.78rem', color: 'var(--text-tertiary)', marginBottom: '0.5rem' }}>
              {proposalNotice}
            </div>
          )}
          {proposal && (
            <div className="ai-result-box">
              <div className="ai-result-header">
                <h4>Proposta: {EDIT_MODES.find((m) => m.id === proposal.mode)?.label}</h4>
                <div className="ai-result-actions">
                  <button type="button" className="action-btn-sm" onClick={handleRejectProposal} title="Descartar sem alterar nada">
                    <X size={14} />
                    <span>Rejeitar</span>
                  </button>
                  <button type="button" className="action-btn-sm primary" onClick={handleAcceptProposal} title="Validar e aplicar atomicamente">
                    <Check size={14} />
                    <span>Aceitar</span>
                  </button>
                </div>
              </div>
              {proposal.explanation && (
                <div style={{ fontSize: '0.8rem', marginBottom: '0.5rem' }}>{proposal.explanation}</div>
              )}
              {proposal.operations.map((op, i) => {
                const target = speech.blocks.find((b) => b.id === op.targetId);
                const before = target ? stripHtmlToText(target.contentHtml) : '(bloco não encontrado)';
                return (
                  <div key={i} className="ai-result-content" style={{ marginBottom: '0.5rem' }}>
                    {op.type === 'replace' && (
                      <>
                        <div style={{ fontSize: '0.72rem', fontWeight: 700 }}>ANTES</div>
                        <div style={{ fontSize: '0.8rem', opacity: 0.85 }}>{before.slice(0, 600)}</div>
                        <div style={{ fontSize: '0.72rem', fontWeight: 700, marginTop: '0.4rem' }}>DEPOIS</div>
                        <div style={{ fontSize: '0.8rem' }}>{stripHtmlToText(op.contentHtml).slice(0, 1200)}</div>
                      </>
                    )}
                    {op.type === 'insert' && (
                      <>
                        <div style={{ fontSize: '0.72rem', fontWeight: 700 }}>+ SERÁ INSERIDO {op.position === 'before' ? 'ANTES' : 'APÓS'} “{target?.title}”</div>
                        <div style={{ fontSize: '0.8rem' }}>{stripHtmlToText(op.contentHtml).slice(0, 1200)}</div>
                      </>
                    )}
                    {op.type === 'delete' && (
                      <>
                        <div style={{ fontSize: '0.72rem', fontWeight: 700 }}>− SERÁ REMOVIDO</div>
                        <div style={{ fontSize: '0.8rem', opacity: 0.85 }}>{before.slice(0, 600)}</div>
                      </>
                    )}
                  </div>
                );
              })}
              <div style={{ display: 'flex', gap: '0.5rem', marginTop: '0.5rem', alignItems: 'center' }}>
                <button
                  type="button"
                  className="action-btn-sm"
                  onClick={handleVerifyProposal}
                  disabled={verifying}
                  title="Verifica o suporte do conteúdo proposto (não aplica)"
                >
                  <span>{verifying ? 'Verificando...' : '🔍 Verificar proposta'}</span>
                </button>
                {proposalVerification && (
                  <span style={{ fontSize: '0.72rem', color: 'var(--text-tertiary)' }}>
                    ✓ {proposalVerification.summary.supported} · ⚠ {proposalVerification.summary.partial} ·{' '}
                    ? {proposalVerification.summary.insufficient} · 💡 {proposalVerification.summary.creative}
                  </span>
                )}
              </div>
            </div>
          )}
        </div>

        {/* Loading Indicator */}
        {isLoading && (
          <div
            className="copilot-section-output"
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
            <button
              type="button"
              className="action-btn-sm"
              onClick={handleCancel}
              title="Cancelar geração"
              style={{ marginTop: '0.75rem' }}
            >
              <Square size={14} />
              <span>Cancelar</span>
            </button>
          </div>
        )}

        {/* AI Action Result Box */}
        {resultText && !isLoading && (
          <div className="ai-result-box copilot-section-output">
            <div className="ai-result-header">
              <h4>{resultTitle}</h4>
              {resultMeta && (
                <div style={{ fontSize: '0.7rem', color: 'var(--text-tertiary)' }} title="Diagnóstico técnico da chamada">
                  via {resultMeta.providerId} · {resultMeta.model} · {(resultMeta.durationMs / 1000).toFixed(1)}s
                  {resultMeta.offline ? ' · offline' : ''}
                </div>
              )}
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
        <div className="copilot-section-output">
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

        {/* Fase 15: composer fixo no fim do painel (§7) — o caminho principal */}
        <div className="chat-composer" data-testid="chat-composer">
          {chatMessages.length === 0 && (
            <div className="chat-quick-actions" aria-label="Ações rápidas">
              {QUICK_ACTIONS.map((qa) => (
                <button
                  key={qa.id}
                  type="button"
                  className="chat-chip"
                  disabled={chatPending}
                  onClick={() => void sendChatMessage(qa.message)}
                >
                  {qa.label}
                  <span className="visually-hidden"> — enviar mensagem</span>
                </button>
              ))}
            </div>
          )}
          <div className="chat-input-row">
            <label className="visually-hidden" htmlFor="chat-input">Digite uma mensagem</label>
            <textarea
              id="chat-input"
              className="chat-input"
              placeholder="Digite uma mensagem..."
              value={chatDraft}
              rows={1}
              disabled={isOffline && !hasApiKey}
              aria-label="Digite uma mensagem"
              onChange={(e) => setChatDraft(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault();
                  if (chatDraft.trim()) void sendChatMessage(chatDraft);
                }
              }}
            />
            <button
              type="button"
              className="chat-send-btn"
              aria-label="Enviar mensagem"
              title="Enviar (Enter)"
              disabled={!chatDraft.trim() || chatPending}
              onClick={() => {
                if (chatDraft.trim()) void sendChatMessage(chatDraft);
              }}
            >
              <Send size={16} aria-hidden="true" />
            </button>
          </div>
        </div>
      </div>
    </aside>
  );
};
