package com.radikk.app.data.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.radikk.app.MainActivity
import com.radikk.app.R
import com.radikk.app.util.RadikoTimeUtil
import java.time.Instant

/**
 * 番組開始通知を表示する BroadcastReceiver。
 * ReminderScheduler が AlarmManager に登録した時刻に発火する。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val stationId = intent.getStringExtra(EXTRA_STATION_ID) ?: return
        val stationName = intent.getStringExtra(EXTRA_STATION_NAME) ?: return
        val programTitle = intent.getStringExtra(EXTRA_PROGRAM_TITLE) ?: return
        val startEpochMillis = intent.getLongExtra(EXTRA_START_EPOCH, 0L)
        val endEpochMillis = intent.getLongExtra(EXTRA_END_EPOCH, 0L)
        val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID) ?: return

        // Android 13+ で POST_NOTIFICATIONS 未許可の場合は通知を表示できない。
        // 通知権限をリクエストする手段はここ (BroadcastReceiver) にはないため、
        // 何も表示せず静かにスキップする (クラッシュさせない)。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel(notificationManager)

        // 通知タップでその番組を再生する (ライブ再生: 通知時刻=放送開始時刻)
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = MainActivity.ACTION_PLAY_FROM_REMINDER
            putExtra(EXTRA_STATION_ID, stationId)
            putExtra(EXTRA_STATION_NAME, stationName)
            putExtra(EXTRA_PROGRAM_TITLE, programTitle)
            putExtra(EXTRA_START_EPOCH, startEpochMillis)
            putExtra(EXTRA_END_EPOCH, endEpochMillis)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            reminderId.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val timeText = RadikoTimeUtil.formatTime(Instant.ofEpochMilli(startEpochMillis))
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(programTitle)
            .setContentText("$stationName $timeText から放送開始")
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notificationManager.notify(reminderId.hashCode(), notification)
    }

    private fun createChannel(notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "番組開始通知",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "番組表から設定した放送開始時刻の通知"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "program_reminders"
        const val EXTRA_REMINDER_ID = "extra_reminder_id"
        const val EXTRA_STATION_ID = "extra_station_id"
        const val EXTRA_STATION_NAME = "extra_station_name"
        const val EXTRA_PROGRAM_TITLE = "extra_program_title"
        const val EXTRA_START_EPOCH = "extra_start_epoch"
        const val EXTRA_END_EPOCH = "extra_end_epoch"
    }
}
