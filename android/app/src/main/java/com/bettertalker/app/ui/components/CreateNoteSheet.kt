package com.bettertalker.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 5.5 — porta única de criação de esboço (Home e Assistente).
 *
 * A nota só nasce depois da escolha; o chamador decide o que fazer com o
 * resultado (abrir Preparo, vincular ao Assistente ou pedir o texto).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateNoteSheet(
    onDismiss: () -> Unit,
    onImportS34: () -> Unit,
    onPasteVm: () -> Unit,
    onStartFromScratch: () -> Unit,
    onQuickNote: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                "Criar esboço",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            CreateNoteOption(
                icon = Icons.Default.UploadFile,
                title = "Importar esboço S-34",
                subtitle = "JWPUB ou DOCX (PDF como fallback)",
                onClick = onImportS34,
            )
            CreateNoteOption(
                icon = Icons.Default.ContentPaste,
                title = "Colar parte da reunião V&M",
                subtitle = "Texto da parte (S-38)",
                onClick = onPasteVm,
            )
            CreateNoteOption(
                icon = Icons.Default.Create,
                title = "Criar esboço do zero",
                subtitle = "Discurso ou demonstração",
                onClick = onStartFromScratch,
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onQuickNote) { Text("Nota rápida") }
            }
        }
    }
}

@Composable
private fun CreateNoteOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
