package com.bettertalker.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bettertalker.app.ui.theme.PaletteId
import com.bettertalker.app.ui.theme.ThemeMode
import com.bettertalker.app.ui.theme.paletteLabel

/**
 * #10b — dialog de Aparência (tema + paleta), extraído da Home para a
 * SettingsScreen. Callbacks puros: quem chama persiste no SettingsStore
 * e fecha o dialog.
 */
@Composable
fun AppearanceDialog(
    themeMode: ThemeMode,
    paletteId: PaletteId,
    onThemeChange: (ThemeMode) -> Unit,
    onPaletteChange: (PaletteId) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aparência") },
        text = {
            Column {
                ThemeMode.entries.forEach { m ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onThemeChange(m); onDismiss() }
                            .padding(vertical = 8.dp)
                    ) {
                        RadioButton(
                            selected = themeMode == m,
                            onClick = { onThemeChange(m); onDismiss() },
                        )
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
                Spacer(Modifier.height(12.dp))
                // #7d3: segunda seção — paleta curada.
                Text("Paleta", style = MaterialTheme.typography.titleSmall)
                PaletteId.entries.forEach { p ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPaletteChange(p); onDismiss() }
                            .padding(vertical = 8.dp)
                    ) {
                        RadioButton(
                            selected = paletteId == p,
                            onClick = { onPaletteChange(p); onDismiss() },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(paletteLabel(p))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } }
    )
}
