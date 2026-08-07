package com.radikk.app.data.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 端末再起動後に、保存済みのリマインダーを AlarmManager へ再登録する。
 * RECEIVE_BOOT_COMPLETED 権限が必要。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            val repo = ReminderRepository(context)
            val scheduler = ReminderScheduler(context)
            scheduler.rescheduleAll(repo.currentReminders())
        }
    }
}
