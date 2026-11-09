package com.bettertalker.app.ui.stage

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bettertalker.app.ui.theme.relativeLuminance

/**
 * v5.3: renderizador único do diálogo de demonstração.
 *
 * Usado na Tribuna (roteiro completo, sem destaque) e no Palco (roteiro
 * completo com o turno atual destacado; [autoScroll] leva o destaque para a
 * área visível). Blocos sem papel aparecem como narração, nunca são
 * descartados.
 */
@Composable
fun DemoScript(
    turns: List<StageTurn>,
    fontSp: Int,
    modifier: Modifier = Modifier,
    /** Índice do turno atual no roteiro; null = leitura sem destaque. */
    currentIndex: Int? = null,
    /** Papel do orador (destaca as falas dele). */
    role: String? = null,
    /** Rola o container até o turno atual quando ele muda. */
    autoScroll: Boolean = false,
    /** v5.3: usa a paleta de alto contraste do Palco em vez do tema do app. */
    stageStyle: Boolean = false,
    emptyText: String = "Diálogo ainda não escrito — edite no editor.",
) {
    if (turns.isEmpty()) {
        Text(
            emptyText,
            style = MaterialTheme.typography.bodyMedium,
            color = if (stageStyle) StageDim else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val speakers = turns
        .filter { it.kind == StageTurnKind.SPEECH }
        .map { it.speaker }
        .distinct()
    val darkTheme = relativeLuminance(MaterialTheme.colorScheme.surface) < 0.5
    val currentRequester = remember(currentIndex) { BringIntoViewRequester() }
    LaunchedEffect(currentIndex, turns, autoScroll) {
        if (autoScroll && currentIndex != null && currentIndex in turns.indices) {
            withFrameNanos { }
            currentRequester.bringIntoView()
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        turns.forEachIndexed { index, turn ->
            val isCurrent = currentIndex == index
            val itemModifier = if (isCurrent && autoScroll) {
                Modifier.bringIntoViewRequester(currentRequester)
            } else {
                Modifier
            }
            if (turn.kind == StageTurnKind.NARRATION) {
                NarrationBlock(
                    text = turn.text,
                    fontSp = fontSp,
                    isCurrent = isCurrent,
                    stageStyle = stageStyle,
                    modifier = itemModifier,
                )
            } else {
                SpeechBlock(
                    turn = turn,
                    speakerIndex = speakers.indexOf(turn.speaker).coerceAtLeast(0),
                    darkTheme = darkTheme,
                    fontSp = fontSp,
                    isCurrent = isCurrent,
                    isRole = role != null && turn.speaker.equals(role, ignoreCase = true),
                    stageStyle = stageStyle,
                    modifier = itemModifier,
                )
            }
        }
    }
}

@Composable
private fun NarrationBlock(
    text: String,
    fontSp: Int,
    isCurrent: Boolean,
    stageStyle: Boolean,
    modifier: Modifier,
) {
    val labelColor = if (stageStyle) StageDim else MaterialTheme.colorScheme.onSurfaceVariant
    val container = if (stageStyle) Color(0xFF111827) else MaterialTheme.colorScheme.surfaceContainerLow
    val content = if (stageStyle) StageDim else MaterialTheme.colorScheme.onSurfaceVariant
    val borderColor = if (stageStyle) StageAccent else MaterialTheme.colorScheme.primary
    Column(modifier.fillMaxWidth()) {
        Text(
            "NARRAÇÃO",
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
        )
        Spacer(Modifier.height(2.dp))
        Surface(
            color = container,
            shape = RoundedCornerShape(10.dp),
            border = if (isCurrent) {
                BorderStroke(2.dp, borderColor)
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = fontSp.sp),
                color = content,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}

@Composable
private fun SpeechBlock(
    turn: StageTurn,
    speakerIndex: Int,
    darkTheme: Boolean,
    fontSp: Int,
    isCurrent: Boolean,
    isRole: Boolean,
    stageStyle: Boolean,
    modifier: Modifier,
) {
    val alignRight = speakerIndex % 2 == 0
    val container = when {
        stageStyle && isCurrent -> StageAccent.copy(alpha = 0.24f)
        stageStyle && isRole -> StageAccent.copy(alpha = 0.16f)
        stageStyle -> Color(0xFF111827)
        isCurrent -> MaterialTheme.colorScheme.primaryContainer
        isRole -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = when {
        stageStyle -> StageFg
        isCurrent -> MaterialTheme.colorScheme.onPrimaryContainer
        isRole -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val speakerColor = when {
        stageStyle && (isCurrent || isRole) -> StageAccent
        stageStyle -> StageDim
        isCurrent -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> stageSpeakerColor(speakerIndex, dark = darkTheme)
    }
    val borderColor = if (stageStyle) StageAccent else MaterialTheme.colorScheme.primary
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = if (alignRight) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            color = container,
            shape = RoundedCornerShape(14.dp),
            border = if (isCurrent) {
                BorderStroke(2.dp, borderColor)
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth(0.86f),
        ) {
            Column(Modifier.padding(10.dp)) {
                Text(
                    turn.speaker + if (isRole) " · você" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = speakerColor,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    turn.text,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSp.sp),
                    color = content,
                )
            }
        }
    }
}
