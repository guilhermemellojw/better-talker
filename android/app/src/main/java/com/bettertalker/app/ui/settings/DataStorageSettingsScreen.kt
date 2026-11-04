package com.bettertalker.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataStorageSettingsScreen(
    onBack: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                title = { Text("Dados e armazenamento") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar")
                    }
                },
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).padding(20.dp)) {
            SubSectionTile(
                title = "Backup",
                subtitle = "Exportar e importar dados (ZIP)",
                onClick = onOpenBackup,
            )
            Spacer(Modifier.height(8.dp))
            SubSectionTile(
                title = "Biblioteca",
                subtitle = "Publicações importadas",
                onClick = onOpenLibrary,
            )
        }
    }
}

@Composable
private fun SubSectionTile(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
