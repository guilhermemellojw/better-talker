package com.bettertalker.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bettertalker.app.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("settings")

class SettingsStore(private val ctx: Context) {
    private val SORT = stringPreferencesKey("sort")
    val sort: Flow<String> = ctx.store.data.map { it[SORT] ?: "updated" }
    suspend fun setSort(v: String) { ctx.store.edit { it[SORT] = v } }

    private val THEME = stringPreferencesKey("theme_mode")
    val themeMode: Flow<ThemeMode> = ctx.store.data.map {
        runCatching { ThemeMode.valueOf(it[THEME] ?: "AUTO") }.getOrDefault(ThemeMode.AUTO)
    }
    suspend fun setThemeMode(v: ThemeMode) { ctx.store.edit { it[THEME] = v.name } }
}
