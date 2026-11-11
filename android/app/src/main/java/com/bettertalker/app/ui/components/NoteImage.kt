package com.bettertalker.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import com.bettertalker.app.data.db.NoteImageEntity
import java.io.File

/** Rótulo curto do tipo de imagem (UI). */
fun noteImageKindLabel(kind: String): String = when (kind) {
    "chart" -> "Gráfico"
    "photo" -> "Foto"
    "cover" -> "Capa"
    else -> "Ilustração"
}

@Composable
fun NoteImageThumb(
    path: String,
    caption: String,
    modifier: Modifier = Modifier,
    sizeDp: Int = 48,
) {
    val ctx = LocalContext.current
    if (path.isBlank()) {
        Box(
            modifier = modifier
                .size(sizeDp.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Baixando…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val request = remember(path) {
        ImageRequest.Builder(ctx)
            .data(File(path))
            .size(256)
            .crossfade(true)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = caption.ifBlank { "Imagem da seção" },
        contentScale = ContentScale.Crop,
        placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
        error = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(8.dp)),
    )
}

@Composable
fun NoteImageFullScreen(
    path: String,
    caption: String,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val request = remember(path) {
        ImageRequest.Builder(ctx).data(File(path)).size(Size.ORIGINAL).build()
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                if (path.isBlank()) {
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Imagem ainda não baixada",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                } else {
                    AsyncImage(
                        model = request,
                        contentDescription = caption.ifBlank { "Imagem da seção" },
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Row(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Fechar imagem", tint = Color.White)
                    }
                }
                if (caption.isNotBlank()) {
                    Text(
                        caption,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * Tira discreta de miniaturas (40–48dp no editor, 64–72dp no Treino/Palco).
 * Toque abre a imagem em tela cheia; não abre sozinho.
 */
@Composable
fun NoteImageStrip(
    images: List<NoteImageEntity>,
    modifier: Modifier = Modifier,
    thumbSizeDp: Int = 48,
    captionColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    if (images.isEmpty()) return
    var preview by remember { mutableStateOf<NoteImageEntity?>(null) }
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(images, key = { it.id }) { image ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { preview = image },
            ) {
                NoteImageThumb(
                    path = image.path,
                    caption = image.caption,
                    sizeDp = thumbSizeDp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)),
                )
                if (image.caption.isNotBlank()) {
                    Text(
                        image.caption,
                        style = MaterialTheme.typography.labelSmall,
                        color = captionColor,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
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
