package com.bettertalker.app.ui.stage

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Treino MVP — preparação antes da sessão (spec `treino-mvp.md` §5).
 *
 * Escopo (inteiro/seção/subponto), modo (análise × leitura) e cronômetro.
 * Nada começa aqui: o CTA "Começar treino" é o único gatilho do timer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingPrepScreen(
    noteTitle: String,
    sections: List<TrainSectionOption>,
    prep: TrainPrep,
    analysisAvailable: Boolean,
    hasText: Boolean,
    ready: Boolean,
    onPrepChange: (TrainPrep) -> Unit,
    onStart: (TrainPrep) -> Unit,
    onBack: () -> Unit,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val scopeDetail = trainScopeDetail(prep.scope, sections)

    Box(Modifier.fillMaxSize().background(StageBg)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 22.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Preparar treino",
                        color = StageFg,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    if (noteTitle.isNotBlank()) {
                        Text(
                            noteTitle,
                            color = StageDim,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.Close, "Voltar", tint = StageFg)
                }
            }

            Spacer(Modifier.height(22.dp))
            PrepSectionTitle("Escopo")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrepChoice(
                    label = "Discurso inteiro",
                    selected = prep.scope.kind == TrainScopeKind.WHOLE,
                    modifier = Modifier.weight(1f),
                    onClick = { onPrepChange(prep.copy(scope = TrainScope.Whole)) },
                )
                PrepChoice(
                    label = if (prep.scope.kind == TrainScopeKind.WHOLE) {
                        "Escolher trecho"
                    } else {
                        "Trocar trecho"
                    },
                    selected = prep.scope.kind != TrainScopeKind.WHOLE,
                    modifier = Modifier.weight(1f),
                    onClick = { pickerOpen = true },
                )
            }
            if (scopeDetail != null) {
                Text(
                    scopeDetail,
                    color = StageDim,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Spacer(Modifier.height(22.dp))
            PrepSectionTitle("Modo")
            ModeCard(
                title = "Com análise de fala",
                subtitle = if (analysisAvailable) {
                    "Microfone aberto; métricas no fim. Nada é gravado."
                } else {
                    "Reconhecimento de voz indisponível neste aparelho."
                },
                selected = prep.mode == TrainMode.ANALYSIS,
                enabled = analysisAvailable,
                onClick = { onPrepChange(prep.copy(mode = TrainMode.ANALYSIS)) },
            )
            Spacer(Modifier.height(10.dp))
            ModeCard(
                title = "Só leitura",
                subtitle = "Sem microfone e sem análise. O texto e o timer continuam.",
                selected = prep.mode == TrainMode.READING,
                enabled = true,
                onClick = { onPrepChange(prep.copy(mode = TrainMode.READING)) },
            )

            Spacer(Modifier.height(22.dp))
            PrepSectionTitle("Cronômetro")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrepChoice(
                    label = "Ligado",
                    selected = prep.timerEnabled,
                    modifier = Modifier.weight(1f),
                    onClick = { onPrepChange(prep.copy(timerEnabled = true)) },
                )
                PrepChoice(
                    label = "Sem cronômetro",
                    selected = !prep.timerEnabled,
                    modifier = Modifier.weight(1f),
                    onClick = { onPrepChange(prep.copy(timerEnabled = false)) },
                )
            }

            Spacer(Modifier.height(28.dp))
            if (!ready) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = StageAccent,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Carregando a nota…", color = StageDim)
                }
            } else {
                if (!hasText) {
                    Text(
                        "Esta nota ainda não tem texto para o treino.",
                        color = StageAccent,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                Button(
                    onClick = { onStart(prep) },
                    enabled = hasText,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StageAccent,
                        contentColor = Color(0xFF111827),
                    ),
                ) {
                    Text("Começar treino", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
            Text(
                "O cronômetro só começa no Começar treino. Áudio não é gravado; " +
                    "a transcrição é descartada após as métricas.",
                color = StageDim,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 12.dp, bottom = 20.dp),
            )
        }
    }

    if (pickerOpen) {
        ScopePickerSheet(
            sections = sections,
            scope = prep.scope,
            onPick = { scope ->
                onPrepChange(prep.copy(scope = scope))
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

/** Resumo do trecho escolhido (seção + subponto); null no discurso inteiro. */
private fun trainScopeDetail(
    scope: TrainScope,
    sections: List<TrainSectionOption>,
): String? {
    if (scope.kind == TrainScopeKind.WHOLE) return null
    val section = sections.firstOrNull { it.id == scope.sectionId } ?: return null
    val duration = if (section.minutes > 0) " · meta ${section.minutes} min" else ""
    return when (scope.kind) {
        TrainScopeKind.SECTION -> "Seção: ${section.title}$duration"
        TrainScopeKind.SUBPOINT -> {
            val sub = section.subPoints.firstOrNull { it.id == scope.subPointId }
            val label = sub?.text ?: "Subponto"
            "Trecho de ${section.title}: $label$duration"
        }
        TrainScopeKind.WHOLE -> null
    }
}

@Composable
private fun PrepSectionTitle(text: String) {
    Text(
        text.uppercase(),
        color = StageAccent,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun PrepChoice(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) StageAccent.copy(alpha = 0.18f) else Color(0xFF111827),
        contentColor = if (selected) StageFg else StageDim,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            1.dp,
            if (selected) StageAccent else Color.White.copy(alpha = 0.12f),
        ),
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 46.dp),
    ) {
        Box(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ModeCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val contentAlpha = if (enabled) 1f else 0.45f
    Surface(
        color = if (selected) StageAccent.copy(alpha = 0.18f) else Color(0xFF111827),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            1.dp,
            if (selected) StageAccent else Color.White.copy(alpha = 0.12f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = if (selected) StageFg else StageFg.copy(alpha = contentAlpha),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    subtitle,
                    color = StageDim.copy(alpha = contentAlpha),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (selected) {
                Text("✓", color = StageAccent, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScopePickerSheet(
    sections: List<TrainSectionOption>,
    scope: TrainScope,
    onPick: (TrainScope) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            "Escolher trecho",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Text(
            "A meta do trecho é a duração da seção.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 520.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            item(key = "whole") {
                ScopeRow(
                    title = "Discurso inteiro",
                    subtitle = "Todas as seções",
                    selected = scope.kind == TrainScopeKind.WHOLE,
                    onClick = { onPick(TrainScope.Whole) },
                )
            }
            sections.forEach { section ->
                item(key = "section-${section.id}") {
                    ScopeRow(
                        title = section.title,
                        subtitle = if (section.minutes > 0) {
                            "Seção · ${section.minutes} min"
                        } else {
                            "Seção"
                        },
                        selected = scope.kind == TrainScopeKind.SECTION &&
                            scope.sectionId == section.id,
                        onClick = {
                            onPick(TrainScope(TrainScopeKind.SECTION, sectionId = section.id))
                        },
                    )
                }
                items(section.subPoints, key = { "sub-${it.id}" }) { sub ->
                    ScopeRow(
                        title = "• ${sub.text}",
                        subtitle = if (section.minutes > 0) {
                            "Subponto · meta ${section.minutes} min"
                        } else {
                            "Subponto"
                        },
                        selected = scope.kind == TrainScopeKind.SUBPOINT &&
                            scope.subPointId == sub.id,
                        indented = true,
                        onClick = {
                            onPick(
                                TrainScope(
                                    kind = TrainScopeKind.SUBPOINT,
                                    sectionId = section.id,
                                    subPointId = sub.id,
                                )
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ScopeRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    indented: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else Color.Transparent,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(
                start = if (indented) 30.dp else 10.dp,
                end = 10.dp,
                top = 10.dp,
                bottom = 10.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Text(
                    "✓",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
