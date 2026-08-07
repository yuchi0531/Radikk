package com.radikk.app.data.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.Instant

/**
 * AlarmManager による番組開始通知のスケジュール。
 */
class ReminderScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * 放送開始時刻に通知するようスケジュールする。
     * 開始時刻が過去なら何もしない。
     *
     * 正確なアラーム (setExact) は SCHEDULE_EXACT_ALARM 権限が必要で、
     * Play ストアのポリシーで許可されないため、非正確アラーム (set) を使用する。
     * 通知は数分程度の遅延は許容される。
     */
    fun schedule(reminder: StoredReminder) {
        val startMillis = reminder.startEpochMillis
        if (startMillis <= Instant.now().toEpochMilli()) return

        val pendingIntent = buildPendingIntent(reminder, createRequestCode(reminder.id))
        alarmManager.set(AlarmManager.RTC_WAKEUP, startMillis, pendingIntent)
    }

    /** スケジュールを解除する。 */
    fun cancel(reminderId: String) {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_FIRE_REMINDER
            putExtra(ReminderReceiver.EXTRA_REMINDER_ID, reminderId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            createRequestCode(reminderId),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        pendingIntent?.cancel()
        alarmManager.cancel(pendingIntent)
    }

    /** 全リマインダーを再スケジュールする (アプリ起動時・再起動後)。 */
    fun rescheduleAll(reminders: List<StoredReminder>) {
        reminders.forEach { schedule(it) }
    }

    private fun buildPendingIntent(reminder: StoredReminder, requestCode: Int): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_FIRE_REMINDER
            putExtra(ReminderReceiver.EXTRA_REMINDER_ID, reminder.id)
            putExtra(ReminderReceiver.EXTRA_STATION_ID, reminder.stationId)
            putExtra(ReminderReceiver.EXTRA_STATION_NAME, reminder.stationName)
            putExtra(ReminderReceiver.EXTRA_PROGRAM_TITLE, reminder.programTitle)
            putExtra(ReminderReceiver.EXTRA_START_EPOCH, reminder.startEpochMillis)
            putExtra(ReminderReceiver.EXTRA_END_EPOCH, reminder.endEpochMillis)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_FIRE_REMINDER = "com.radikk.app.action.FIRE_REMINDER"

        /** リマインダーID から PendingIntent の requestCode を生成 (正の値に丸める)。 */
        fun createRequestCode(reminderId: String): Int =
            reminderId.hashCode() and 0x7fffffff
    }
}
