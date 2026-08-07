package com.radikk.app.data.reminder

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.radikk.app.util.RadikoTimeUtil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

private val Context.reminderDataStore by preferencesDataStore(name = "radikk_reminders")

/**
 * 番組開始通知 (リマインダー) 設定の永続化 (DataStore Preferences)。
 *
 * キー: リマインダーID (局ID + 開始時刻のエポックミリ秒)
 * 値: [StoredReminder] の JSON
 */
@Serializable
data class StoredReminder(
    val id: String,
    val stationId: String,
    val stationName: String,
    val programTitle: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
)

class ReminderRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private val remindersFlow: Flow<Map<String, String>> = context.reminderDataStore.data
        .map { p ->
            p.asMap().entries.filter { it.key.name.startsWith(PREFIX) }
                .associate { it.key.name.removePrefix(PREFIX) to it.value.toString() }
        }

    /** 全リマインダー。開始時刻順にソート済み。 */
    val reminders: Flow<List<StoredReminder>> = remindersFlow.map { map ->
        map.values.mapNotNull { v ->
            runCatching { json.decodeFromString<StoredReminder>(v) }.getOrNull()
        }.sortedBy { it.startEpochMillis }
    }

    /** 指定の番組のリマインダーが設定済みか。 */
    suspend fun isSet(stationId: String, startEpochMillis: Long): Boolean {
        val id = reminderId(stationId, startEpochMillis)
        return context.reminderDataStore.data.first().contains(stringPreferencesKey(PREFIX + id))
    }

    /** リマインダーを追加する。既存なら上書き。 */
    suspend fun add(reminder: StoredReminder) {
        context.reminderDataStore.edit { p ->
            p[stringPreferencesKey(PREFIX + reminder.id)] = json.encodeToString(reminder)
        }
    }

    /** リマインダーを削除する。 */
    suspend fun remove(id: String) {
        context.reminderDataStore.edit { p ->
            p.remove(stringPreferencesKey(PREFIX + id))
        }
    }

    suspend fun removeByProgram(stationId: String, startEpochMillis: Long) {
        remove(reminderId(stationId, startEpochMillis))
    }

    /** 現在のリマインダー一覧を一度だけ読む。 */
    suspend fun currentReminders(): List<StoredReminder> = reminders.first()

    companion object {
        private const val PREFIX = "reminder_"

        /** リマインダーID = 局ID + 開始時刻 (同一番組は一意)。 */
        fun reminderId(stationId: String, startEpochMillis: Long): String {
            val date = RadikoTimeUtil.formatJst14(Instant.ofEpochMilli(startEpochMillis))
            return "$stationId-$date"
        }
    }
}
