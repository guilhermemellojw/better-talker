package com.bettertalker.app.ui.copilot

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bettertalker.app.data.copilot.DiagnosisCriterion
import com.bettertalker.app.data.copilot.DiagnosisCriterionId
import com.bettertalker.app.data.copilot.DiagnosisLevel
import com.bettertalker.app.data.copilot.DiagnosisNextStep
import com.bettertalker.app.data.copilot.DiagnosisPoint
import com.bettertalker.app.data.copilot.DiagnosisS34Status
import com.bettertalker.app.data.copilot.OutlineDiagnosis
import com.bettertalker.app.data.copilot.diagnosisPointsVisibility

/**
 * Card do Mentor para o diagnóstico estrutural do esboço.
 *
 * Read-only: nenhum botão gera texto ou edita a nota. “Abrir/Desenvolver”
 * navega para a seção; “Vincular/Reimportar S-34” abre o attach sheet;
 * “Mostrar fontes” reusa o fluxo de sources; “Reanalisar” refaz o diagnóstico.
 */
@Composable
internal fun OutlineDiagnosisCard(
    report: OutlineDiagnosis,
    onOpenSection: (DiagnosisNextStep.OpenSection) -> Unit,
    onOpenNote: () -> Unit,
    onShowSources: () -> Unit,
    onAttachS34: () -> Unit,
    onReanalyze: () -> Unit,
) {
    val statusColor = diagnosisColor(report.overall)
    var expanded by remember(report.generatedAt) { mutableStateOf(false) }
    val visibility = diagnosisPointsVisibility(report.points, expanded)

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Mentor · Diagnóstico do esboço",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        report.headline,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor,
                    )
                }
                DiagnosisStatusBadge(report.overall)
                IconButton(onClick = onReanalyze) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reanalisar")
                }
            }
            Text(
                report.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            HorizontalDivider(Modifier.padding(vertical = 10.dp))

            Row(verticalAlignment = Alignment.Top) {
                Text(
                    s34Icon(report.s34Status),
                    color = s34Color(report.s34Status),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(22.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        s34Label(report.s34Status),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        report.s34Detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            report.criteria.forEach { criterion ->
                CriterionRow(
                    criterion = criterion,
                    onAction = {
                        when (criterion.id) {
                            DiagnosisCriterionId.SOURCES -> onShowSources()
                            else -> onOpenNote()
                        }
                    },
                )
            }

            if (report.points.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Pontos",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    val attention = report.points.count { it.level != DiagnosisLevel.OK }
                    if (attention > 0) {
                        Text(
                            "$attention com atenção",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                if (visibility.compactOnly) {
                    Text(
                        "${report.points.size} pontos sem pendências.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                visibility.visible.forEach { point ->
                    PointRow(point, onOpenSection)
                }
                if (visibility.showToggle) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(
                            if (expanded) "Recolher"
                            else "Ver todos (${report.points.size})"
                        )
                    }
                }
            }

            if (report.priorities.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Text(
                    "Prioridades",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                report.priorities.forEach { priority ->
                    Text(
                        "• $priority",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Text(
                "Próximo passo",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    when (val step = report.nextStep) {
                        is DiagnosisNextStep.OpenSection -> onOpenSection(step)
                        is DiagnosisNextStep.OpenNote -> onOpenNote()
                        is DiagnosisNextStep.ShowSources -> onShowSources()
                        is DiagnosisNextStep.LinkS34,
                        is DiagnosisNextStep.ReimportS34 -> onAttachS34()
                        is DiagnosisNextStep.Reanalyze -> onReanalyze()
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) {
                Text(report.nextStep.label)
            }
            Text(
                "Análise estrutural. Aplicação, transições e linguagem entram na mentoria do ponto.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun CriterionRow(
    criterion: DiagnosisCriterion,
    onAction: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        Text(
            levelIcon(criterion.level),
            color = diagnosisColor(criterion.level),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(22.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                criterion.label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                criterion.finding,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            criterion.action?.let { action ->
                Text(
                    action,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier
                        .clickable(onClick = onAction)
                        .padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun PointRow(
    point: DiagnosisPoint,
    onOpenSection: (DiagnosisNextStep.OpenSection) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        Text(
            levelIcon(point.level),
            color = diagnosisColor(point.level),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(22.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                "Ponto ${point.number} · ${point.title}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${point.minutes} min · ${point.finding}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = {
                onOpenSection(
                    DiagnosisNextStep.OpenSection(
                        sectionId = point.sectionId,
                        title = point.title,
                        sectionNumber = point.number,
                        label = point.actionLabel,
                    )
                )
            },
        ) {
            Text(point.actionLabel)
        }
    }
}

@Composable
private fun DiagnosisStatusBadge(level: DiagnosisLevel) {
    val color = diagnosisColor(level)
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            statusLabel(level),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun diagnosisColor(level: DiagnosisLevel): Color = when (level) {
    DiagnosisLevel.OK -> MaterialTheme.colorScheme.primary
    DiagnosisLevel.ATTENTION -> MaterialTheme.colorScheme.tertiary
    DiagnosisLevel.MISSING -> MaterialTheme.colorScheme.error
    DiagnosisLevel.NOT_INFORMED,
    DiagnosisLevel.NOT_ASSESSED -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun s34Color(status: DiagnosisS34Status): Color = when (status) {
    DiagnosisS34Status.LINKED -> MaterialTheme.colorScheme.primary
    DiagnosisS34Status.MISSING -> MaterialTheme.colorScheme.error
    DiagnosisS34Status.NO_STRUCTURE -> MaterialTheme.colorScheme.tertiary
}

private fun s34Icon(status: DiagnosisS34Status): String = when (status) {
    DiagnosisS34Status.LINKED -> "✓"
    DiagnosisS34Status.MISSING -> "×"
    DiagnosisS34Status.NO_STRUCTURE -> "!"
}

private fun s34Label(status: DiagnosisS34Status): String = when (status) {
    DiagnosisS34Status.LINKED -> "S-34 vinculado"
    DiagnosisS34Status.MISSING -> "S-34 não vinculado"
    DiagnosisS34Status.NO_STRUCTURE -> "S-34 sem estrutura"
}

private fun levelIcon(level: DiagnosisLevel): String = when (level) {
    DiagnosisLevel.OK -> "✓"
    DiagnosisLevel.ATTENTION -> "!"
    DiagnosisLevel.MISSING -> "×"
    DiagnosisLevel.NOT_INFORMED,
    DiagnosisLevel.NOT_ASSESSED -> "?"
}

private fun statusLabel(level: DiagnosisLevel): String = when (level) {
    DiagnosisLevel.OK -> "OK"
    DiagnosisLevel.ATTENTION -> "ATENÇÃO"
    DiagnosisLevel.MISSING -> "FALTANDO"
    DiagnosisLevel.NOT_INFORMED -> "NÃO INFORMADO"
    DiagnosisLevel.NOT_ASSESSED -> "NÃO AVALIADO"
}
