package com.bettertalker.app.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bettertalker.app.data.db.AttachmentEntity

/**
 * 3.2.5g.3 — sheet de anexos da nota (substitui o rodapé fixo do editor).
 *
 * Padrão ModalBottomSheet (espelha o AttachSheet do chat): lista os anexos
 * com desvincular por arquivo (por id) e um botão para anexar da Biblioteca.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentsSheet(
    attachments: List<AttachmentEntity>,
    onUnlink: (id: String) -> Unit,
    onAttach: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Anexos", style = MaterialTheme.typography.titleMedium)

            if (attachments.isEmpty()) {
                Text(
                    "Nenhum anexo ainda.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    attachments.forEach { a ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(a.fileName, style = MaterialTheme.typography.bodyMedium)
                                if (a.error != null) {
                                    Text(
                                        a.error,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            IconButton(onClick = { onUnlink(a.id) }) {
                                Icon(Icons.Default.Close, "Desvincular")
                            }
                        }
                    }
                }
            }

            OutlinedButton(onClick = onAttach, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.AttachFile, null)
                Spacer(Modifier.width(4.dp))
                Text("+ Anexar da Biblioteca")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
