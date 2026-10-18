package com.bettertalker.app.ui.hub

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bettertalker.app.ui.components.NoteCard
import com.bettertalker.app.ui.components.NoteRowCompact
import com.bettertalker.app.ui.components.NoteRowDetailed
import com.bettertalker.app.ui.home.HomeViewMode

/**
 * Lista compartilhada dos hubs (Treinar/Apresentar) nos 3 modos de
 * visualização (o mesmo `HomeViewMode` global da Home): Grade (mosaico),
 * Lista compacta e Lista detalhada. Cada modo mantém o próprio estado de
 * rolagem (içado com remember) e a troca anima com fade de 180ms.
 */
@Composable
internal fun HubNoteList(
    cards: List<HubNoteCard>,
    mode: HomeViewMode,
    emptyText: String,
    onOpen: (String) -> Unit,
) {
    if (cards.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
        return
    }
    val gridScroll = rememberLazyStaggeredGridState()
    val compactScroll = rememberLazyListState()
    val detailedScroll = rememberLazyListState()
    AnimatedContent(
        targetState = mode,
        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
        modifier = Modifier.fillMaxSize(),
    ) { m ->
        when (m) {
            HomeViewMode.GRID -> LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Adaptive(160.dp),
                contentPadding = PaddingValues(16.dp),
                verticalItemSpacing = 12.dp,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                state = gridScroll,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(cards, key = { it.id }) { card ->
                    HubNoteItem(card, HomeViewMode.GRID, onOpen)
                }
            }
            HomeViewMode.LIST_COMPACT -> LazyColumn(
                state = compactScroll,
                contentPadding = PaddingValues(vertical = 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(cards, key = { it.id }) { card ->
                    HubNoteItem(card, HomeViewMode.LIST_COMPACT, onOpen)
                }
            }
            HomeViewMode.LIST_DETAILED -> LazyColumn(
                state = detailedScroll,
                contentPadding = PaddingValues(vertical = 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(cards, key = { it.id }) { card ->
                    HubNoteItem(card, HomeViewMode.LIST_DETAILED, onOpen)
                }
            }
        }
    }
}

/** Card do hub no modo escolhido: badge (tipo) + duração extras. */
@Composable
private fun HubNoteItem(
    card: HubNoteCard,
    mode: HomeViewMode,
    onOpen: (String) -> Unit,
) {
    val badge = if (card.isDemonstration) "Demonstração" else "Discurso"
    val duration = card.minutes?.let { "$it min" }
    val onClick = { onOpen(card.id) }
    when (mode) {
        HomeViewMode.GRID -> NoteCard(
            title = card.title, md = card.preview, updatedAt = card.updatedAt,
            pinned = card.pinned, attachCount = 0, colorArgb = card.colorArgb,
            onClick = onClick, badgeText = badge, durationText = duration,
        )
        HomeViewMode.LIST_COMPACT -> NoteRowCompact(
            title = card.title, updatedAt = card.updatedAt, pinned = card.pinned,
            colorArgb = card.colorArgb, onClick = onClick,
            badgeText = badge, durationText = duration,
        )
        HomeViewMode.LIST_DETAILED -> NoteRowDetailed(
            title = card.title, md = card.preview, updatedAt = card.updatedAt,
            pinned = card.pinned, colorArgb = card.colorArgb, onClick = onClick,
            badgeText = badge, durationText = duration,
        )
    }
}
