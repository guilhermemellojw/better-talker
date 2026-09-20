package com.bettertalker.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bettertalker.app.data.prefs.SettingsStore
import com.bettertalker.app.ui.components.NoteCard
import com.bettertalker.app.ui.theme.NOTE_COLORS
import com.bettertalker.app.ui.theme.ThemeMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onOpenNote: (String) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenAccount: () -> Unit,
    settings: SettingsStore
) {
    val notes by vm.notes.collectAsState()
    val folders by vm.folders.collectAsState()
    val query by vm.query.collectAsState()
    val folderId by vm.folderId.collectAsState()
    val counts by vm.attachCounts.collectAsState()
    val themeMode by settings.themeMode.collectAsState(initial = ThemeMode.AUTO)
    val scope = rememberCoroutineScope()
    var showTheme by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }

    if (showTheme) {
        AlertDialog(
            onDismissRequest = { showTheme = false },
            title = { Text("Aparência") },
            text = {
                Column {
                    ThemeMode.entries.forEach { m ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch { settings.setThemeMode(m) }
                                    showTheme = false
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            RadioButton(selected = themeMode == m, onClick = {
                                scope.launch { settings.setThemeMode(m) }
                                showTheme = false
                            })
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when (m) {
                                    ThemeMode.AUTO -> "Automático (sistema)"
                                    ThemeMode.LIGHT -> "Claro"
                                    ThemeMode.DARK -> "Escuro"
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTheme = false }) { Text("Fechar") } }
        )
    }

    if (showNewFolder) {
        var name by remember { mutableStateOf("") }
        var color by remember { mutableStateOf(NOTE_COLORS.first().value.toLong()) }
        AlertDialog(
            onDismissRequest = { showNewFolder = false },
            title = { Text("Nova pasta") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("Nome") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NOTE_COLORS.forEach { c ->
                            Box(
                                Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(c)
                                    .clickable { color = c.value.toLong() }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) vm.newFolder(name.trim(), color)
                    showNewFolder = false
                }) { Text("Criar") }
            },
            dismissButton = { TextButton(onClick = { showNewFolder = false }) { Text("Cancelar") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Better Talker") },
                actions = {
                    IconButton(onClick = { showTheme = true }) { Icon(Icons.Default.Contrast, "Aparência") }
                    IconButton(onClick = onOpenLibrary) { Icon(Icons.Default.Book, "Biblioteca") }
                    IconButton(onClick = onOpenTrash) { Icon(Icons.Default.Delete, "Lixeira") }
                    IconButton(onClick = onOpenAccount) { Icon(Icons.Default.AccountCircle, "Conta") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { scope.launch { onOpenNote(vm.newNote()) } }) {
                Icon(Icons.Default.Add, "Nova nota")
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            OutlinedTextField(
                value = query, onValueChange = vm::setQuery,
                placeholder = { Text("Buscar notas…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(8.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item {
                    FilterChip(selected = folderId == null, onClick = { vm.setFolder(null) }, label = { Text("Todas") })
                }
                items(folders) { f ->
                    FilterChip(
                        selected = folderId == f.id, onClick = { vm.setFolder(if (folderId == f.id) null else f.id) },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(f.colorArgb.toULong())))
                                Spacer(Modifier.width(6.dp))
                                Text(f.name)
                            }
                        }
                    )
                }
                item {
                    IconButton(onClick = { showNewFolder = true }) {
                        Icon(Icons.Default.CreateNewFolder, "Nova pasta")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (notes.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nenhuma nota. Toque + para criar.", color = MaterialTheme.colorScheme.secondary)
                }
            } else {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Adaptive(160.dp),
                    contentPadding = PaddingValues(16.dp),
                    verticalItemSpacing = 12.dp,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(notes, key = { it.id }) { n ->
                        NoteCard(
                            title = n.title, md = n.mdText, updatedAt = n.updatedAt,
                            pinned = n.pinned, attachCount = counts[n.id] ?: 0,
                            colorArgb = n.colorArgb,
                            onClick = { onOpenNote(n.id) },
                            onDelete = { vm.trash(n.id) }
                        )
                    }
                }
            }
        }
    }
}
