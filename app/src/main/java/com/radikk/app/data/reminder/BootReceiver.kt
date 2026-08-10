package com.radikk.app.data.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 端末再起動後に、保存済みのリマインダーを AlarmManager へ再登録する。
 * RECEIVE_BOOT_COMPLETED 権限が必要。
 *
 * DataStore 読み込み + 再スケジュールは非同期のため goAsync() でブロードキャストを
 * 延命し、完了後に finish() する。これによりプロセスが強制終了されて
 * 再スケジュールが途中で失われるのを防ぐ。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val repo = ReminderRepository(context)
                val scheduler = ReminderScheduler(context)
                // すでに開始済み (放送中または終了済み) のリマインダーは再スケジュール不要
                val nowMillis = Instant.now().toEpochMilli()
                val upcoming = repo.currentReminders().filter { it.startEpochMillis > nowMillis }
                scheduler.rescheduleAll(upcoming)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
