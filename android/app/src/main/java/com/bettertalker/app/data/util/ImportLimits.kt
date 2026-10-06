package com.bettertalker.app.data.util

/**
 * Tetos de importação por tipo de arquivo.
 *
 * - **Esboços** são texto de 2–3 páginas (≤ ~10MB com folga).
 * - **Publicações** (BYOD) incluem JWPUBs grandes: o maior atual é o
 *   `it_T.jwpub` unificado (Estudo Perspicaz, 354MB).
 */
object ImportLimits {
    const val OUTLINE_BYTES = 10L * 1024 * 1024
    const val LIBRARY_BYTES = 400L * 1024 * 1024
}
