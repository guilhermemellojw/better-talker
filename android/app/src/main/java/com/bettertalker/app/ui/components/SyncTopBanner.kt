package com.bettertalker.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp

/**
 * Estados do banner de sincronização (pull-to-refresh da Home).
 * O ViewModel emite; o banner só desenha.
 */
enum class SyncUiState { Idle, Syncing, Success, NoLogin, Error }

/**
 * Banner premium de sincronização: pill que desliza do topo central —
 * nuvem girando + "Sincronizando…", check + "Tudo atualizado!" (o VM recolhe
 * sozinho depois de 2s) e aviso para erro/sem login.
 */
@Composable
fun SyncTopBanner(
    state: SyncUiState,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state != SyncUiState.Idle,
        modifier = modifier,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shadowElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (state) {
                    SyncUiState.Syncing -> {
                        // Nuvem girando: 0→360 em loop enquanto sincroniza.
                        val angle by animateFloatAsState(
                            targetValue = 360f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 1200, easing = LinearEasing),
                            ),
                            label = "syncSpin",
                        )
                        Icon(
                            Icons.Outlined.CloudSync, null,
                            modifier = Modifier.size(20.dp).rotate(angle),
                        )
                        Text("Sincronizando...", style = MaterialTheme.typography.labelLarge)
                    }
                    SyncUiState.Success -> {
                        Icon(
                            Icons.Default.CheckCircle, null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Text("Tudo atualizado!", style = MaterialTheme.typography.labelLarge)
                    }
                    SyncUiState.NoLogin, SyncUiState.Error -> {
                        Icon(
                            Icons.Default.WarningAmber, null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            if (state == SyncUiState.NoLogin) "Faça login para sincronizar"
                            else "Sem conexão — tente de novo",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    SyncUiState.Idle -> Unit
                }
            }
        }
    }
}
