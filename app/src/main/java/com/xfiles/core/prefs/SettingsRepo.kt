package com.xfiles.core.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class SortBy { NAME, SIZE, DATE, TYPE }
enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepo(private val context: Context) {

    private val keyShowHidden = booleanPreferencesKey("show_hidden")
    private val keySortBy = stringPreferencesKey("sort_by")
    private val keySortDescending = booleanPreferencesKey("sort_descending")
    private val keyDirsFirst = booleanPreferencesKey("dirs_first")
    private val keyThemeMode = stringPreferencesKey("theme_mode")
    private val keyDynamicColor = booleanPreferencesKey("dynamic_color")

    val showHidden: Flow<Boolean> = context.dataStore.data.map { it[keyShowHidden] ?: false }
    val sortBy: Flow<SortBy> =
        context.dataStore.data.map { runCatching { SortBy.valueOf(it[keySortBy] ?: "") }.getOrDefault(SortBy.NAME) }
    val sortDescending: Flow<Boolean> = context.dataStore.data.map { it[keySortDescending] ?: false }
    val dirsFirst: Flow<Boolean> = context.dataStore.data.map { it[keyDirsFirst] ?: true }
    val themeMode: Flow<ThemeMode> =
        context.dataStore.data.map { runCatching { ThemeMode.valueOf(it[keyThemeMode] ?: "") }.getOrDefault(ThemeMode.SYSTEM) }
    val dynamicColor: Flow<Boolean> = context.dataStore.data.map { it[keyDynamicColor] ?: true }

    suspend fun setShowHidden(value: Boolean) = context.dataStore.edit { it[keyShowHidden] = value }
    suspend fun setSortBy(value: SortBy) = context.dataStore.edit { it[keySortBy] = value.name }
    suspend fun setSortDescending(value: Boolean) = context.dataStore.edit { it[keySortDescending] = value }
    suspend fun setDirsFirst(value: Boolean) = context.dataStore.edit { it[keyDirsFirst] = value }
    suspend fun setThemeMode(value: ThemeMode) = context.dataStore.edit { it[keyThemeMode] = value.name }
    suspend fun setDynamicColor(value: Boolean) = context.dataStore.edit { it[keyDynamicColor] = value }
}
