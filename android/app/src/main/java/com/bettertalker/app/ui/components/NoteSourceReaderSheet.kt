package com.bettertalker.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bettertalker.app.data.planning.SourceReaderContent
import com.bettertalker.app.data.planning.SourceReaderStatus
import com.bettertalker.app.data.util.NoteSource

/** Estado do reader aberto a partir do sheet de fontes da nota/S-34. */
sealed interface NoteSourceReaderState {
    val source: NoteSource

    data class Loading(override val source: NoteSource) : NoteSourceReaderState
    data class Content(
        override val source: NoteSource,
        val content: SourceReaderContent,
    ) : NoteSourceReaderState

    data class Error(
        override val source: NoteSource,
        val message: String,
    ) : NoteSourceReaderState
}

/**
 * 6.0a/P0 — reader da fonte citada.
 *
 * Mostra o trecho que o prompt usou e o TEXTO COMPLETO da referência no
 * índice. Miss/indexação/texto não encontrado explicam o motivo e oferecem
 * "Abrir Biblioteca"; nunca abrem um trecho aleatório.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteSourceReaderSheet(
    state: NoteSourceReaderState,
    onDismiss: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Fonte citada",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "Fechar fonte")
                }
            }

            when (state) {
                is NoteSourceReaderState.Loading -> {
                    Row(
                        Modifier.fillMaxWidth().height(140.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.padding(horizontal = 6.dp))
                        Text("Abrindo fonte…")
                    }
                }

                is NoteSourceReaderState.Error -> {
                    Text(
                        state.message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    TextButton(onClick = onOpenLibrary) { Text("Abrir Biblioteca") }
                }

                is NoteSourceReaderState.Content -> {
                    ReaderContent(
                        content = state.content,
                        onOpenLibrary = onOpenLibrary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderContent(
    content: SourceReaderContent,
    onOpenLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.verticalScroll(rememberScrollState()),
    ) {
        Text(
            content.publicationTitle,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (content.referenceLabel.isNotBlank()) {
            Text(
                content.referenceLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        if (content.locationLabel.isNotBlank()) {
            Text(
                content.locationLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        content.interpretation?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        val unavailable = content.status in setOf(
            SourceReaderStatus.MISSING,
            SourceReaderStatus.INDEXING,
            SourceReaderStatus.TEXT_NOT_FOUND,
        )
        if (unavailable) {
            Text(
                content.reason ?: "Fonte indisponível neste aparelho.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 10.dp),
            )
            TextButton(onClick = onOpenLibrary) { Text("Abrir Biblioteca") }
        }

        if (content.usedText != null) {
            Spacer(Modifier.height(14.dp))
            Text(
                "Trecho usado na resposta",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            SelectionContainer {
                Text(
                    content.usedText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (content.body.length > content.usedText.length) {
                Text(
                    "O Copilot viu ${content.usedText.length} de " +
                        "${content.body.length} caracteres.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        if (content.body.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            Text(
                "Texto completo da referência",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            SelectionContainer {
                Text(
                    content.body,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        if (content.truncated) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Unidade muito extensa: o reader mostra o limite de segurança. " +
                    "Use a Biblioteca para consultar a edição completa.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        Spacer(Modifier.height(10.dp))
    }
}
