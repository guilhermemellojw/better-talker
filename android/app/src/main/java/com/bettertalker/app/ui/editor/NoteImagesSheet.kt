package com.bettertalker.app.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bettertalker.app.data.db.NoteImageEntity
import com.bettertalker.app.ui.components.NoteImageFullScreen
import com.bettertalker.app.ui.components.NoteImageThumb
import com.bettertalker.app.ui.components.noteImageKindLabel

private val IMAGE_KINDS = listOf("illustration", "chart", "photo", "cover")

/**
 * Sheet de imagens de UMA seção (ou da nota, sectionId = null).
 * A imagem é anexo: não entra no HTML nem no RAG.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteImagesSheet(
    sectionId: String?,
    images: List<NoteImageEntity>,
    onAdd: (android.net.Uri) -> Unit,
    onDelete: (String) -> Unit,
    onCaption: (String, String) -> Unit,
    onKind: (String, String) -> Unit,
    onMove: (String, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) onAdd(uri) }
    var preview by remember { mutableStateOf<NoteImageEntity?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            Text(
                if (sectionId == null) "Imagens da nota" else "Imagens da seção",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "A imagem fica anexada à nota; a legenda é o que a IA recebe (só texto).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            OutlinedButton(
                onClick = {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
            ) {
                Icon(Icons.Default.AddPhotoAlternate, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Adicionar imagem")
            }
            if (images.isEmpty()) {
                Text(
                    "Nenhuma imagem ainda.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(images, key = { it.id }) { image ->
                        ImageEditorRow(
                            image = image,
                            onPreview = { preview = image },
                            onDelete = { onDelete(image.id) },
                            onCaption = { onCaption(image.id, it) },
                            onKind = { onKind(image.id, it) },
                            onMove = { onMove(image.id, it) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    preview?.let { image ->
        NoteImageFullScreen(
            path = image.path,
            caption = image.caption,
            onDismiss = { preview = null },
        )
    }
}

@Composable
private fun ImageEditorRow(
    image: NoteImageEntity,
    onPreview: () -> Unit,
    onDelete: () -> Unit,
    onCaption: (String) -> Unit,
    onKind: (String) -> Unit,
    onMove: (Int) -> Unit,
) {
    var caption by remember(image.id) { mutableStateOf(image.caption) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NoteImageThumb(
                path = image.path,
                caption = image.caption,
                sizeDp = 56,
                modifier = Modifier.clickable { onPreview() },
            )
            Spacer(Modifier.width(10.dp))
            OutlinedTextField(
                value = caption,
                onValueChange = {
                    caption = it
                    onCaption(it)
                },
                label = { Text("Legenda") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onMove(-1) }) {
                Icon(Icons.Default.ArrowUpward, "Mover para cima")
            }
            IconButton(onClick = { onMove(1) }) {
                Icon(Icons.Default.ArrowDownward, "Mover para baixo")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, "Remover imagem")
            }
        }
        LazyRow(
            Modifier
                .fillMaxWidth()
                .padding(start = 66.dp, top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(IMAGE_KINDS, key = { it }) { kind ->
                FilterChip(
                    selected = image.kind == kind,
                    onClick = { onKind(kind) },
                    label = { Text(noteImageKindLabel(kind), maxLines = 1) },
                )
            }
        }
    }
}
