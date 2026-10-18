package com.bettertalker.app.ui.theme

/**
 * Texturas de fundo sutis ("skeuomorfismo moderno"): aplicadas ATRÁS do
 * conteúdo (background global) — desenhadas proceduralmente, sem PNG.
 * Persistida em `home_texture` no SettingsStore.
 */
enum class TextureId {
    NONE,
    PAPER,
    DOTS,
    GRID,
    NOISE,
    ;

    companion object {
        /** Nome persistido → enum; desconhecido/nulo cai em [NONE] (nunca quebra). */
        fun fromNameOrDefault(name: String?): TextureId =
            entries.firstOrNull { it.name == name } ?: NONE
    }
}

/** Rótulo amigável da textura (tela de Aparência). Puro/testável. */
fun textureLabel(t: TextureId): String = when (t) {
    TextureId.NONE -> "Sem textura"
    TextureId.PAPER -> "Papel"
    TextureId.DOTS -> "Pontilhado"
    TextureId.GRID -> "Quadriculado"
    TextureId.NOISE -> "Ruído"
}
