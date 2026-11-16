package com.bettertalker.app.ui.copilot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bettertalker.app.data.copilot.DiagnosisLevel
import com.bettertalker.app.data.copilot.PointCoachingFinding
import com.bettertalker.app.data.copilot.PointCoachingNextStep
import com.bettertalker.app.data.copilot.PointCoachingReport

/**
 * Card do Mentor para um ponto: leitura, diagnóstico, caminho e próximo passo.
 * Read-only. O único caminho para texto é o botão explícito “Gerar rascunho”.
 */
@Composable
internal fun PointCoachingCard(
    report: PointCoachingReport,
    onOpenEditor: (PointCoachingNextStep.OpenEditor) -> Unit,
    onGenerateDraft: (Int) -> Unit,
    onReanalyze: () -> Unit,
) {
    val statusColor = coachingColor(report.overall)
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
                    Icons.Default.Lightbulb,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Mentor · Ponto ${report.pointNumber}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        report.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                CoachingStatusBadge(report.overall)
                IconButton(onClick = onReanalyze) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reanalisar ponto")
                }
            }
            Text(
                report.reading,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            HorizontalDivider(Modifier.padding(vertical = 10.dp))

            report.findings.forEach { finding ->
                FindingRow(finding)
            }

            if (report.steps.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Text(
                    "Caminho",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                report.steps.forEachIndexed { index, step ->
                    Text(
                        "${index + 1}. $step",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
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
                        is PointCoachingNextStep.OpenEditor -> onOpenEditor(step)
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) {
                Text(report.nextStep.label)
            }
            TextButton(
                onClick = { onGenerateDraft(report.pointNumber) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Gerar rascunho")
            }
            Text(
                "Mentoria estrutural. O rascunho é opcional e entra no fluxo normal do Copilot.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun FindingRow(finding: PointCoachingFinding) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        Text(
            coachingIcon(finding.level),
            color = coachingColor(finding.level),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(22.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                finding.label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                finding.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CoachingStatusBadge(level: DiagnosisLevel) {
    val color = coachingColor(level)
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            coachingLabel(level),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun coachingColor(level: DiagnosisLevel): Color = when (level) {
    DiagnosisLevel.OK -> MaterialTheme.colorScheme.primary
    DiagnosisLevel.ATTENTION -> MaterialTheme.colorScheme.tertiary
    DiagnosisLevel.MISSING -> MaterialTheme.colorScheme.error
    DiagnosisLevel.NOT_INFORMED,
    DiagnosisLevel.NOT_ASSESSED -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun coachingIcon(level: DiagnosisLevel): String = when (level) {
    DiagnosisLevel.OK -> "✓"
    DiagnosisLevel.ATTENTION -> "!"
    DiagnosisLevel.MISSING -> "×"
    DiagnosisLevel.NOT_INFORMED,
    DiagnosisLevel.NOT_ASSESSED -> "?"
}

private fun coachingLabel(level: DiagnosisLevel): String = when (level) {
    DiagnosisLevel.OK -> "OK"
    DiagnosisLevel.ATTENTION -> "ATENÇÃO"
    DiagnosisLevel.MISSING -> "FALTANDO"
    DiagnosisLevel.NOT_INFORMED -> "NÃO INFORMADO"
    DiagnosisLevel.NOT_ASSESSED -> "NÃO AVALIADO"
}
