package com.bettertalker.app.ui.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bettertalker.app.data.db.NoteEntity
import com.bettertalker.app.ui.components.NoteRowCompact

/**
 * Landing da aba Assistente quando ainda não há nota ativa.
 *
 * Nada abre sozinho: a lista é só um atalho para o editor; o chat só aparece
 * depois que o usuário escolhe uma nota. PTT entra na fatia 3.
 */
@Composable
fun AssistantLanding(
    notes: List<NoteEntity>,
    loading: Boolean,
    onOpenNote: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(20.dp))
        Text(
            "Assistente",
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Abra uma nota para conversar com o Copilot.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        when {
            loading -> Box(
                Modifier.fillMaxWidth().padding(top = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            notes.isEmpty() -> Column(
                Modifier.fillMaxWidth().padding(top = 32.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "Nenhum esboço ainda.",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "Crie um esboço na aba Esboços; depois ele aparece aqui para conversar.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                Text(
                    "Notas recentes",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(notes.take(8), key = { it.id }) { note ->
                        NoteRowCompact(
                            title = note.title,
                            updatedAt = note.updatedAt,
                            pinned = note.pinned,
                            colorArgb = note.colorArgb,
                            onClick = { onOpenNote(note.id) },
                        )
                    }
                }
            }
        }
    }
}
