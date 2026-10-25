package com.bettertalker.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bettertalker.app.domain.planning.SuggestedStructure
import com.bettertalker.app.domain.planning.suggestStructure
import com.bettertalker.app.domain.speech.DiscourseType

/**
 * Fase 3 — "Criar esboço do zero": formulário com título, tipo, duração e
 * pontos. A prévia usa a mesma função pura que cria a nota, então o que o
 * usuário vê é o que será persistido.
 *
 * Demonstração livre nasce com 1 BODY; discurso público com
 * Intro 1 + N pontos + Conclusão 1. Nada de S-38/parte da reunião.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StartFromScratchSheet(
    onDismiss: () -> Unit,
    onCreate: (String, SuggestedStructure) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var title by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(DiscourseType.S34_DISCOURSE) }
    var durationText by remember { mutableStateOf("10") }
    var points by remember { mutableIntStateOf(3) }

    val duration = durationText.toIntOrNull()
    val structure = remember(duration, type, points) {
        duration?.let { suggestStructure(it, type, points) }
    }
    val titleOk = title.trim().isNotBlank()
    val canCreate = titleOk && structure != null

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Criar esboço do zero",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Título") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Tipo", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(
                    DiscourseType.S34_DISCOURSE to "Discurso público",
                    DiscourseType.FREE_DEMO to "Demonstração livre",
                ).forEachIndexed { index, (t, label) ->
                    SegmentedButton(
                        selected = type == t,
                        onClick = { type = t },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = 2,
                        ),
                    ) { Text(label) }
                }
            }

            Text("Duração (min)", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 15, 30, 45).forEach { m ->
                    FilterChip(
                        selected = duration == m,
                        onClick = { durationText = m.toString() },
                        label = { Text("$m") },
                    )
                }
            }
            OutlinedTextField(
                value = durationText,
                onValueChange = { durationText = it.filter(Char::isDigit).take(2) },
                label = { Text("Minutos") },
                singleLine = true,
                isError = durationText.isNotBlank() && structure == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(160.dp),
            )

            if (type == DiscourseType.S34_DISCOURSE) {
                Text("Pontos", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { if (points > 1) points-- },
                        enabled = points > 1,
                    ) { Icon(Icons.Default.Remove, "Menos pontos") }
                    Text(
                        "$points",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(32.dp),
                    )
                    IconButton(
                        onClick = { if (points < 5) points++ },
                        enabled = points < 5,
                    ) { Icon(Icons.Default.Add, "Mais pontos") }
                }
            }

            if (!titleOk) {
                Text(
                    "Informe o título.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (structure == null) {
                Text(
                    validationMessage(type, duration),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                if (structure.adjusted && type == DiscourseType.S34_DISCOURSE) {
                    Text(
                        "Ajustado para ${structure.effectivePoints} pontos para caber na duração.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Text("Prévia", style = MaterialTheme.typography.labelLarge)
                structure.sections.forEach { section ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(section.title, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${section.minutes} min",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Total", fontWeight = FontWeight.SemiBold)
                    Text(
                        "${structure.totalMinutes} min",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Button(
                onClick = {
                    val s = structure
                    if (canCreate && s != null) onCreate(title.trim(), s)
                },
                enabled = canCreate,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Criar nota") }

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text("Cancelar") }
        }
    }
}

private fun validationMessage(type: DiscourseType, duration: Int?): String = when {
    duration == null -> "Informe a duração."
    type == DiscourseType.S34_DISCOURSE && duration < 3 ->
        "Discurso público exige pelo menos 3 min."
    type == DiscourseType.FREE_DEMO && duration < 2 ->
        "Demonstração livre exige pelo menos 2 min."
    duration > 45 -> "Máximo: 45 min."
    else -> "Duração inválida."
}
