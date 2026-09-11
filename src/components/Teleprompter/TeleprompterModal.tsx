import { useState, useEffect, useRef } from 'react';
import type { Speech } from '../../types/speech';
import { Play, Pause, RotateCcw, X, ZoomIn, ZoomOut, FlipHorizontal, Clock } from 'lucide-react';

interface TeleprompterModalProps {
  speech: Speech;
  isOpen: boolean;
  onClose: () => void;
  defaultWpm?: number;
}

export const TeleprompterModal = ({
  speech,
  isOpen,
  onClose,
  defaultWpm = 130,
}: TeleprompterModalProps) => {
  const [isPlaying, setIsPlaying] = useState(false);
  const [wpm, setWpm] = useState(defaultWpm);
  const [fontSize, setFontSize] = useState(36);
  const [isMirrored, setIsMirrored] = useState(false);
  const [elapsedSeconds, setElapsedSeconds] = useState(0);
  const [progressPercent, setProgressPercent] = useState(0);
  const [controlsVisible] = useState(true);

  const scrollContainerRef = useRef<HTMLDivElement>(null);
  const animationFrameRef = useRef<number | null>(null);
  const timerIntervalRef = useRef<number | null>(null);

  // Keyboard controls: Space to play/pause, Escape to exit
  useEffect(() => {
    if (!isOpen) return;

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.code === 'Space') {
        e.preventDefault();
        setIsPlaying((prev) => !prev);
      } else if (e.code === 'Escape') {
        e.preventDefault();
        onClose();
      } else if (e.code === 'ArrowUp') {
        setWpm((prev) => Math.min(260, prev + 10));
      } else if (e.code === 'ArrowDown') {
        setWpm((prev) => Math.max(70, prev - 10));
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, onClose]);

  // Elapsed Timer
  useEffect(() => {
    if (isPlaying) {
      timerIntervalRef.current = window.setInterval(() => {
        setElapsedSeconds((prev) => prev + 1);
      }, 1000);
    } else {
      if (timerIntervalRef.current) clearInterval(timerIntervalRef.current);
    }
    return () => {
      if (timerIntervalRef.current) clearInterval(timerIntervalRef.current);
    };
  }, [isPlaying]);

  // Auto-scroll loop
  useEffect(() => {
    if (!isPlaying || !scrollContainerRef.current) {
      if (animationFrameRef.current) cancelAnimationFrame(animationFrameRef.current);
      return;
    }

    let lastTime = performance.now();

    const scrollLoop = (time: number) => {
      const delta = (time - lastTime) / 1000;
      lastTime = time;

      if (scrollContainerRef.current) {
        // Base pixels per second derived from WPM and font size
        // ~2.2 words per line with 36px font, adjust dynamically
        const wordsPerSecond = wpm / 60;
        const linePixelHeight = fontSize * 1.6;
        const pixelsPerSecond = wordsPerSecond * (linePixelHeight / 4.5);

        scrollContainerRef.current.scrollTop += pixelsPerSecond * delta;

        // Calculate progress
        const maxScroll =
          scrollContainerRef.current.scrollHeight - scrollContainerRef.current.clientHeight;
        if (maxScroll > 0) {
          const currentProgress = (scrollContainerRef.current.scrollTop / maxScroll) * 100;
          setProgressPercent(Math.min(100, currentProgress));

          if (scrollContainerRef.current.scrollTop >= maxScroll) {
            setIsPlaying(false);
          }
        }
      }

      animationFrameRef.current = requestAnimationFrame(scrollLoop);
    };

    animationFrameRef.current = requestAnimationFrame(scrollLoop);

    return () => {
      if (animationFrameRef.current) cancelAnimationFrame(animationFrameRef.current);
    };
  }, [isPlaying, wpm, fontSize]);

  const handleReset = () => {
    if (scrollContainerRef.current) {
      scrollContainerRef.current.scrollTop = 0;
    }
    setIsPlaying(false);
    setElapsedSeconds(0);
    setProgressPercent(0);
  };

  const formatTimer = (totalSeconds: number) => {
    const m = Math.floor(totalSeconds / 60);
    const s = totalSeconds % 60;
    return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
  };

  if (!isOpen) return null;

  return (
    <div className="teleprompter-overlay">
      {/* Eye Line Reading Guide */}
      <div className="teleprompter-focus-guide">
        <span className="focus-marker-left">▶</span>
        <span className="focus-marker-right">◀</span>
      </div>

      {/* Floating Top Controls Header */}
      <div className={`teleprompter-header ${controlsVisible ? '' : 'hidden-controls'}`}>
        {/* Timer & Cadence */}
        <div className="teleprompter-timer-box">
          <Clock size={20} style={{ color: 'var(--primary)' }} />
          <span className="tele-timer">{formatTimer(elapsedSeconds)}</span>
          <span className="tele-pace-indicator">
            {wpm} WPM • Meta: {speech.targetDurationMinutes} min
          </span>
        </div>

        {/* Action Controls */}
        <div className="teleprompter-controls">
          {/* Play/Pause */}
          <button
            type="button"
            className="tele-btn play-btn"
            onClick={() => setIsPlaying(!isPlaying)}
            title="Espaço para pausar/retomar"
          >
            {isPlaying ? <Pause size={18} /> : <Play size={18} />}
            <span>{isPlaying ? 'Pausar' : 'Iniciar'}</span>
          </button>

          {/* Reset */}
          <button type="button" className="tele-btn" onClick={handleReset} title="Voltar ao início">
            <RotateCcw size={16} />
          </button>

          {/* Speed Controls */}
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.25rem' }}>
            <button
              type="button"
              className="tele-btn"
              onClick={() => setWpm((prev) => Math.max(70, prev - 10))}
              title="Diminuir velocidade (-10 WPM)"
            >
              -
            </button>
            <span style={{ fontSize: '0.85rem', fontWeight: 700, minWidth: '60px', textAlign: 'center' }}>
              {wpm} PPM
            </span>
            <button
              type="button"
              className="tele-btn"
              onClick={() => setWpm((prev) => Math.min(260, prev + 10))}
              title="Aumentar velocidade (+10 WPM)"
            >
              +
            </button>
          </div>

          {/* Font Size */}
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.2rem' }}>
            <button
              type="button"
              className="tele-btn"
              onClick={() => setFontSize((prev) => Math.max(20, prev - 4))}
              title="Diminuir fonte"
            >
              <ZoomOut size={16} />
            </button>
            <button
              type="button"
              className="tele-btn"
              onClick={() => setFontSize((prev) => Math.min(72, prev + 4))}
              title="Aumentar fonte"
            >
              <ZoomIn size={16} />
            </button>
          </div>

          {/* Mirror Flip for Glass Hardware */}
          <button
            type="button"
            className={`tele-btn ${isMirrored ? 'active' : ''}`}
            onClick={() => setIsMirrored(!isMirrored)}
            title="Espelhar texto horizontalmente (para vidro teleprompter)"
          >
            <FlipHorizontal size={16} />
          </button>

          {/* Exit */}
          <button
            type="button"
            className="tele-btn"
            style={{ background: 'rgba(244, 63, 94, 0.2)', borderColor: 'rgba(244, 63, 94, 0.4)' }}
            onClick={onClose}
            title="Sair do Modo Palco (Esc)"
          >
            <X size={18} />
          </button>
        </div>
      </div>

      {/* Main Reading Canvas */}
      <div
        ref={scrollContainerRef}
        className={`teleprompter-scroll-container ${isMirrored ? 'mirrored' : ''}`}
        onClick={() => setIsPlaying(!isPlaying)}
        style={{ fontSize: `${fontSize}px` }}
      >
        <div
          className="teleprompter-text-body"
          dangerouslySetInnerHTML={{ __html: speech.contentHtml }}
        />
      </div>

      {/* Reading Progress Line */}
      <div className="teleprompter-progress-line" style={{ width: `${progressPercent}%` }} />
    </div>
  );
};
