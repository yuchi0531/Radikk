package com.radikk.app.data.datastore

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * アプリ設定と認証情報の永続化 (DataStore Preferences)。
 *
 * - エリア選択 (47都道府県)
 * - テーマ (ライト/ダーク/自動 + ダイナミックカラー)
 * - ダウンロード先 (SAF tree Uri 文字列。未設定ならアプリ固有領域)
 * - 認証セッション (token / areaId / 有効期限 / device / user)
 */
private val Context.dataStore by preferencesDataStore(name = "radikk_settings")

data class AppSettings(
    val areaId: String = "JP13",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val downloadPath: String? = null,
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class StoredAuth(
    val token: String? = null,
    val areaId: String? = null,
    val areaName: String? = null,
    val device: String? = null,
    val userId: String? = null,
    val expiresAtEpochMillis: Long? = null,
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val AREA_ID = stringPreferencesKey("area_id")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = stringPreferencesKey("dynamic_color")
        val DOWNLOAD_PATH = stringPreferencesKey("download_path")

        val AUTH_TOKEN = stringPreferencesKey("auth_token")
        val AUTH_AREA_ID = stringPreferencesKey("auth_area_id")
        val AUTH_AREA_NAME = stringPreferencesKey("auth_area_name")
        val AUTH_DEVICE = stringPreferencesKey("auth_device")
        val AUTH_USER_ID = stringPreferencesKey("auth_user_id")
        val AUTH_EXPIRES_AT = longPreferencesKey("auth_expires_at")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            areaId = p[Keys.AREA_ID] ?: "JP13",
            themeMode = p[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            dynamicColor = p[Keys.DYNAMIC_COLOR]?.toBoolean() ?: true,
            downloadPath = p[Keys.DOWNLOAD_PATH],
        )
    }

    suspend fun setAreaId(areaId: String) {
        context.dataStore.edit { it[Keys.AREA_ID] = areaId }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled.toString() }
    }

    suspend fun setDownloadPath(path: String) {
        context.dataStore.edit { it[Keys.DOWNLOAD_PATH] = path }
    }

    /** 現在の設定を一度だけ読み取る */
    suspend fun currentSettings(): AppSettings = settings.first()

    // --- 認証情報 ---
    val auth: Flow<StoredAuth> = context.dataStore.data.map { p ->
        StoredAuth(
            token = p[Keys.AUTH_TOKEN],
            areaId = p[Keys.AUTH_AREA_ID],
            areaName = p[Keys.AUTH_AREA_NAME],
            device = p[Keys.AUTH_DEVICE],
            userId = p[Keys.AUTH_USER_ID],
            expiresAtEpochMillis = p[Keys.AUTH_EXPIRES_AT],
        )
    }

    suspend fun currentAuth(): StoredAuth = auth.first()

    suspend fun saveAuth(auth: StoredAuth) {
        context.dataStore.edit { p ->
            auth.token?.let { p[Keys.AUTH_TOKEN] = it } ?: p.remove(Keys.AUTH_TOKEN)
            auth.areaId?.let { p[Keys.AUTH_AREA_ID] = it } ?: p.remove(Keys.AUTH_AREA_ID)
            auth.areaName?.let { p[Keys.AUTH_AREA_NAME] = it } ?: p.remove(Keys.AUTH_AREA_NAME)
            auth.device?.let { p[Keys.AUTH_DEVICE] = it } ?: p.remove(Keys.AUTH_DEVICE)
            auth.userId?.let { p[Keys.AUTH_USER_ID] = it } ?: p.remove(Keys.AUTH_USER_ID)
            auth.expiresAtEpochMillis?.let { p[Keys.AUTH_EXPIRES_AT] = it }
                ?: p.remove(Keys.AUTH_EXPIRES_AT)
        }
    }

    /** 認証キャッシュ削除 (設定画面用) */
    suspend fun clearAuth() {
        context.dataStore.edit { p ->
            p.remove(Keys.AUTH_TOKEN)
            p.remove(Keys.AUTH_AREA_ID)
            p.remove(Keys.AUTH_AREA_NAME)
            p.remove(Keys.AUTH_DEVICE)
            p.remove(Keys.AUTH_USER_ID)
            p.remove(Keys.AUTH_EXPIRES_AT)
        }
    }

    companion object {
        private val EMPTY: Preferences = androidx.datastore.preferences.core.emptyPreferences()
    }
}
