package com.bettertalker.app.ui.home

/**
 * Modos de visualização da Home (persistido em `home_view_mode` no
 * SettingsStore). GRID = cards em mosaico; LIST_COMPACT = linhas de 1 linha;
 * LIST_DETAILED = linhas altas com preview.
 */
enum class HomeViewMode {
    GRID,
    LIST_COMPACT,
    LIST_DETAILED,
    ;

    companion object {
        /** Nome persistido → enum; desconhecido/nulo cai em [GRID] (nunca quebra). */
        fun fromNameOrDefault(name: String?): HomeViewMode =
            entries.firstOrNull { it.name == name } ?: GRID
    }
}

/** Rótulo amigável do modo (menu da Top Bar). Puro/testável. */
fun viewModeLabel(m: HomeViewMode): String = when (m) {
    HomeViewMode.GRID -> "Grade"
    HomeViewMode.LIST_COMPACT -> "Lista compacta"
    HomeViewMode.LIST_DETAILED -> "Lista detalhada"
}
