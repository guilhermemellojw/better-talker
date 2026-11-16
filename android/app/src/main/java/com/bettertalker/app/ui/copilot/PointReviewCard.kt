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
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bettertalker.app.data.copilot.DiagnosisLevel
import com.bettertalker.app.data.copilot.PointReviewNextStep
import com.bettertalker.app.data.copilot.PointReviewReport

/**
 * Card do Mentor para revisão estrutural de um ponto.
 *
 * Read-only: não reescreve o texto. Mostra o que está bom e até 3 melhorias;
 * o usuário edita no editor.
 */
@Composable
internal fun PointReviewCard(
    report: PointReviewReport,
    onOpenEditor: (PointReviewNextStep.OpenEditor) -> Unit,
    onReanalyze: () -> Unit,
) {
    val statusColor = reviewColor(report.overall)
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
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Mentor · Revisão do Ponto ${report.pointNumber}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        report.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ReviewStatusBadge(report.overall)
                IconButton(onClick = onReanalyze) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reanalisar revisão")
                }
            }
            Text(
                report.reading,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            HorizontalDivider(Modifier.padding(vertical = 10.dp))

            if (report.strengths.isNotEmpty()) {
                Text(
                    "O que está bom",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                report.strengths.forEach { strength ->
                    Text(
                        "• $strength",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            if (report.improvements.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Text(
                    "Até 3 melhorias",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                report.improvements.forEachIndexed { index, improvement ->
                    Text(
                        "${index + 1}. $improvement",
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
                        is PointReviewNextStep.OpenEditor -> onOpenEditor(step)
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) {
                Text(report.nextStep.label)
            }
            Text(
                "Revisão estrutural. Não reescrevi seu texto; as melhorias são sugestões.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun ReviewStatusBadge(level: DiagnosisLevel) {
    val color = reviewColor(level)
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            reviewLabel(level),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun reviewColor(level: DiagnosisLevel): Color = when (level) {
    DiagnosisLevel.OK -> MaterialTheme.colorScheme.primary
    DiagnosisLevel.ATTENTION -> MaterialTheme.colorScheme.tertiary
    DiagnosisLevel.MISSING -> MaterialTheme.colorScheme.error
    DiagnosisLevel.NOT_INFORMED,
    DiagnosisLevel.NOT_ASSESSED -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun reviewLabel(level: DiagnosisLevel): String = when (level) {
    DiagnosisLevel.OK -> "OK"
    DiagnosisLevel.ATTENTION -> "ATENÇÃO"
    DiagnosisLevel.MISSING -> "FALTANDO"
    DiagnosisLevel.NOT_INFORMED -> "NÃO INFORMADO"
    DiagnosisLevel.NOT_ASSESSED -> "NÃO AVALIADO"
}
