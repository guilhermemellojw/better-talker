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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bettertalker.app.data.copilot.DiagnosisLevel
import com.bettertalker.app.data.copilot.PointQualityImprovement
import com.bettertalker.app.data.copilot.PointQualityPositive
import com.bettertalker.app.data.copilot.PointQualityStatus
import com.bettertalker.app.data.copilot.PointReviewNextStep
import com.bettertalker.app.data.copilot.PointReviewReport
import com.bettertalker.app.data.copilot.pointQualityCategoryLabel

/**
 * Card do Mentor para revisão de um ponto.
 *
 * Read-only: não reescreve o texto. Quando a revisão qualitativa está
 * disponível, ela é a parte principal; a checagem estrutural determinística
 * continua visível como apoio. Sem LLM/chave/rede, o card determinístico
 * permanece.
 */
@Composable
internal fun PointReviewCard(
    report: PointReviewReport,
    onOpenEditor: (PointReviewNextStep.OpenEditor) -> Unit,
    onReanalyze: () -> Unit,
) {
    val quality = report.quality
    val qualityCompleted = report.qualityStatus == PointQualityStatus.COMPLETED &&
        quality != null
    val badgeLevel = if (qualityCompleted &&
        (quality.improvements.isNotEmpty() || report.overall == DiagnosisLevel.ATTENTION)
    ) {
        DiagnosisLevel.ATTENTION
    } else {
        report.overall
    }
    val statusColor = reviewColor(badgeLevel)
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
                ReviewStatusBadge(badgeLevel)
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

            if (qualityCompleted) {
                QualityStrengths(quality.positives)
                QualityImprovements(quality.improvements)
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Text(
                    "Checagem estrutural",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (report.strengths.isEmpty() && report.improvements.isEmpty()) {
                    Text(
                        "Sem pendências estruturais.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    report.strengths.take(2).forEach {
                        Text(
                            "• $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    report.improvements.take(3).forEach {
                        Text(
                            "• $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
            } else {
                DeterministicReview(report)
            }

            QualityStatusNote(report)

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
                if (qualityCompleted) {
                    "Revisão qualitativa + checagem estrutural. Não reescrevi seu texto; " +
                        "as melhorias são sugestões."
                } else {
                    "Revisão estrutural. Não reescrevi seu texto; as melhorias são sugestões."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun DeterministicReview(report: PointReviewReport) {
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
}

@Composable
private fun QualityStrengths(positives: List<PointQualityPositive>) {
    Text(
        "O que está bom",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
    )
    if (positives.isEmpty()) {
        Text(
            "Nenhum ponto forte específico identificado.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        return
    }
    positives.forEach { positive ->
        Text(
            "• ${positive.observation}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        positive.evidence?.takeIf { it.isNotBlank() }?.let { evidence ->
            Text(
                "Trecho observado: ${evidence.take(220)}",
                style = MaterialTheme.typography.labelSmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp, top = 2.dp),
            )
        }
    }
}

@Composable
private fun QualityImprovements(improvements: List<PointQualityImprovement>) {
    HorizontalDivider(Modifier.padding(vertical = 10.dp))
    Text(
        "Até 3 melhorias",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
    )
    if (improvements.isEmpty()) {
        Text(
            "Nenhuma melhoria específica encontrada; mantenha o texto.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        return
    }
    improvements.take(3).forEachIndexed { index, improvement ->
        Text(
            "${index + 1}. ${pointQualityCategoryLabel(improvement.category)}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            improvement.issue,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            improvement.why,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        improvement.evidence?.takeIf { it.isNotBlank() }?.let { evidence ->
            Text(
                "Trecho observado: ${evidence.take(220)}",
                style = MaterialTheme.typography.labelSmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp, top = 2.dp),
            )
        }
    }
}

@Composable
private fun QualityStatusNote(report: PointReviewReport) {
    val text = when (report.qualityStatus) {
        PointQualityStatus.PENDING -> "Revisão qualitativa em andamento…"
        PointQualityStatus.FAILED,
        PointQualityStatus.SKIPPED,
        PointQualityStatus.STALE -> report.qualityError
            ?: "Revisão qualitativa indisponível; mantive a análise estrutural."
        PointQualityStatus.COMPLETED ->
            if (report.quality?.textTruncated == true) {
                "O texto enviado foi truncado; a revisão qualitativa cobre a parte inicial."
            } else {
                null
            }
        PointQualityStatus.NONE -> null
    }
    text?.takeIf { it.isNotBlank() }?.let {
        Text(
            it,
            style = MaterialTheme.typography.labelSmall,
            color = if (report.qualityStatus == PointQualityStatus.PENDING) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(top = 8.dp),
        )
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
