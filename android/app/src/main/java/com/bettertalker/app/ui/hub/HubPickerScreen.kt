package com.bettertalker.app.ui.hub

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.bettertalker.app.ui.components.NoteRowDetailed
import com.bettertalker.app.ui.present.PresentReadiness
import com.bettertalker.app.ui.present.presentReadinessLabel

/** Picker compartilhado de esboço (Treino/Palco). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubPickerScreen(
    vm: HubViewModel,
    mode: String,
    onBack: () -> Unit,
) {
    val cards by vm.cards.collectAsState()
    val trainId by vm.trainNoteId.collectAsState()
    val presentId by vm.presentNoteId.collectAsState()
    var query by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val filtered = remember(cards, query) {
        val q = query.trim()
        if (q.isBlank()) cards
        else cards.filter {
            it.title.contains(q, ignoreCase = true) ||
                it.preview.contains(q, ignoreCase = true)
        }
    }
    val isTrain = mode == "train"
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.navigationBars,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                title = { Text("Escolher esboço") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Buscar esboço…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, "Limpar busca")
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            if (filtered.isEmpty()) {
                Text(
                    if (query.isBlank()) "Nenhum esboço ainda." else "Nada encontrado para “$query”.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(filtered, key = { it.id }) { card ->
                        NoteRowDetailed(
                            title = hubCardTitle(card.title, card.minutes),
                            md = card.preview,
                            updatedAt = card.updatedAt,
                            pinned = card.pinned,
                            colorArgb = card.colorArgb,
                            onClick = {
                                scope.launch {
                                    if (isTrain) vm.setTrainNoteNow(card.id)
                                    else vm.setPresentNoteNow(card.id)
                                    onBack()
                                }
                            },
                            badgeText = if (card.isDemonstration) "Demonstração" else "Discurso",
                            durationText = card.minutes?.let { "$it min" },
                            statusText = buildString {
                                val current = if (isTrain) trainId == card.id else presentId == card.id
                                if (current) append("Atual · ")
                                if (isTrain) {
                                    append(if (card.hasText) "Tem texto" else "Sem texto")
                                } else {
                                    val readiness = card.presentReadiness ?: PresentReadiness.MISSING
                                    append(
                                        presentReadinessLabel(
                                            readiness,
                                            demo = card.isDemonstration,
                                        )
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
