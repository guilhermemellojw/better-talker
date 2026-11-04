package com.bettertalker.app.ui.library

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bettertalker.app.data.db.AttachmentEntity

/** Semântica única de estado da Biblioteca. */
enum class LibraryStatus(val label: String) {
    READY("Disponível"),
    DOWNLOADING("Baixando…"),
    INDEXING("Indexando…"),
    MISSING("Falta o arquivo"),
    FAILED("Falhou"),
    AMBIGUOUS("Conferir");

    companion object {
        fun fromAttachment(a: AttachmentEntity): LibraryStatus = when (a.status) {
            "ready" -> READY
            "downloading" -> DOWNLOADING
            "missing" -> MISSING
            "failed" -> FAILED
            else -> INDEXING
        }
    }
}

@Composable
fun LibraryStatusPill(status: LibraryStatus, modifier: Modifier = Modifier) {
    val color = when (status) {
        LibraryStatus.READY -> MaterialTheme.colorScheme.secondary
        LibraryStatus.DOWNLOADING,
        LibraryStatus.INDEXING,
        LibraryStatus.MISSING,
        LibraryStatus.AMBIGUOUS -> MaterialTheme.colorScheme.tertiary
        LibraryStatus.FAILED -> MaterialTheme.colorScheme.error
    }
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(50),
    ) {
        Text(
            status.label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
