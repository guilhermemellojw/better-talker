package com.bettertalker.app.ui.editor

import androidx.compose.ui.unit.sp

/**
 * Tamanhos de fonte do editor (picker da toolbar S/M/G).
 *
 * Antes, texto sem span explícito caía no default da lib (~14sp) — pequeno
 * demais para leitura no card. O corpo agora usa [FONT_SIZE_BODY_DEFAULT].
 */
internal val FONT_SIZE_S = 14.sp
internal val FONT_SIZE_M = 18.sp
internal val FONT_SIZE_G = 24.sp

/** Tamanho do texto sem formatação explícita (corpo do editor). */
internal val FONT_SIZE_BODY_DEFAULT = FONT_SIZE_M
