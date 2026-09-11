import { STAGE_CUES } from '../../services/rhetoricEngine';
import type { StageCueDefinition } from '../../types/speech';

interface StageCueBarProps {
  onInsertCue: (cue: StageCueDefinition) => void;
}

export const StageCueBar = ({ onInsertCue }: StageCueBarProps) => {
  return (
    <div className="stage-cue-bar" title="Clique para inserir uma marcação de palco e ritmo no ponto do cursor">
      <span className="cue-bar-label">Marcadores de Palco:</span>
      {STAGE_CUES.map((cue) => (
        <button
          key={cue.id}
          type="button"
          className={`cue-insert-btn ${cue.badgeClass}-btn`}
          onClick={() => onInsertCue(cue)}
          title={cue.tooltip}
        >
          <span>{cue.icon}</span>
          <span>{cue.label}</span>
        </button>
      ))}
    </div>
  );
};
