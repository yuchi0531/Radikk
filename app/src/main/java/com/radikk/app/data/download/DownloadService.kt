package com.radikk.app.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.radikk.app.MainActivity
import com.radikk.app.RadikkApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant

/**
 * タイムフリー番組のダウンロードを実行するフォアグラウンドサービス。
 *
 * ダウンロードは radiko のスライディングウィンドウ配信 (l=300) のため数分〜数十分かかる。
 * viewModelScope で実行すると Activity / ViewModel が破棄された時点 (アプリを
 * バックグラウンド化・スワイプアウト) でキャンセルされてしまうため、
 * 実行をこのサービスに移して UI ライフサイクルから独立させる。
 *
 * - 進行状況は DownloadEvents (プロセス内イベントバス) と通知 (プログレスバー) の両方に流す
 * - 通知の「キャンセル」アクションでダウンロードを中止する (部分ファイルは
 *   DownloadManager 側が削除する)
 * - 完了 / 失敗 / キャンセルは DownloadEvents.messages → アプリの Snackbar に表示
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private var notificationManager: NotificationManager? = null

    /**
     * 通知が利用可能かどうか。
     * Android 13+ で POST_NOTIFICATIONS 未許可のとき startForeground が例外を投げるため、
     * 成功した場合のみ true にして通知関連の処理を有効化する。
     */
    private var notificationsEnabled = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        notificationManager = getSystemService(NotificationManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                // 通知の「キャンセル」アクション
                job?.cancel()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // 実行中のダウンロードがある場合は新しい開始リクエストを無視する
                if (job?.isActive == true) {
                    return START_NOT_STICKY
                }
                val params = DownloadParams.fromIntent(intent) ?: run {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val notification = buildProgressNotification("ダウンロード中", 0f, params.programTitle)
                // Android 13+ で通知権限 (POST_NOTIFICATIONS) が無いと startForeground が
                // 例外 (NotificationRuntimeException / SecurityException) を投げる。
                // クラッシュさせずにダウンロード自体は続行し、通知だけ無効化する。
                notificationsEnabled = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(
                            NOTIFICATION_ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                        )
                    } else {
                        // API 28 以下には型付き startForeground(int, Notification, int) が無い
                        startForeground(NOTIFICATION_ID, notification)
                    }
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "通知が無効のため startForeground に失敗しました: ${e.message}")
                    DownloadEvents.messages.value = "通知が無効のため進捗を表示できません"
                    false
                }
                job = scope.launch {
                    runDownload(params)
                }
                return START_NOT_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** ダウンロード本体。成功時 / 失敗時 / キャンセル時すべてで後始末する。 */
    private suspend fun runDownload(params: DownloadParams) {
        val app = application as RadikkApplication
        val key = "${params.stationId}|${params.ftEpochMillis}"

        // 設定のダウンロード先を AppViewModel と同じロジックで解決する
        val custom = runCatching { app.settingsRepository.currentSettings().downloadPath }.getOrNull()
        val targetTreeUri = if (custom != null && custom.startsWith("content://")) {
            runCatching { Uri.parse(custom) }.getOrNull()
        } else {
            null
        }
        val targetDir = if (custom.isNullOrBlank() || custom.startsWith("content://")) {
            app.getExternalFilesDir(null) ?: app.filesDir
        } else {
            File(custom)
        }

        DownloadEvents.activeKeys.value = DownloadEvents.activeKeys.value + key
        DownloadEvents.progress.value = DownloadEvents.progress.value + (key to 0f)
        notifyProgress(params.programTitle, 0f)

        try {
            val token = app.authRepository.getSession(params.areaId).token
            val entry = app.downloadManager.downloadProgram(
                stationId = params.stationId,
                stationName = params.stationName,
                programTitle = params.programTitle,
                ft = Instant.ofEpochMilli(params.ftEpochMillis),
                to = Instant.ofEpochMilli(params.toEpochMillis),
                token = token,
                targetDir = targetDir,
                targetTreeUri = targetTreeUri,
                context = app,
                imgUrl = params.imgUrl,
                description = params.description,
                performer = params.performer,
                onProgress = { p ->
                    DownloadEvents.progress.value = DownloadEvents.progress.value + (key to p)
                    notifyProgress(params.programTitle, p)
                },
                // 取得途中でトークンが失効した場合 (401) は getSession で再認証して
                // 新しいトークンでそのウィンドウ / セグメントを再試行する
                freshTokenProvider = {
                    runCatching { app.authRepository.getSession(params.areaId).token }.getOrNull()
                },
            )
            val message = "ダウンロード完了: ${entry.programTitle}"
            DownloadEvents.messages.value = message
            notifyCompletion(message)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // キャンセル時はメッセージを出さない (通知の「キャンセル」で止めた場合)。
            // 部分ファイルの削除は DownloadManager 側の catch で処理済み。
            DownloadEvents.messages.value = null
        } catch (e: Exception) {
            val message = e.message ?: "ダウンロードに失敗しました"
            DownloadEvents.messages.value = message
            notifyFailure(message)
        } finally {
            DownloadEvents.activeKeys.value = DownloadEvents.activeKeys.value - key
            DownloadEvents.progress.value = DownloadEvents.progress.value - key
            stopSelf()
        }
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // --- 通知 ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ダウンロード",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "タイムフリー番組のダウンロード進捗"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** ダウンロード進捗通知 (進行中は setOngoing でタップ除去不可)。 */
    private fun buildProgressNotification(
        title: String,
        progress: Float,
        contentTitle: String,
    ): Notification {
        val cancelPi = PendingIntent.getService(
            this,
            1,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(contentTitle)
            .setContentText("ダウンロード中 ${(progress * 100).toInt()}%")
            .setProgress(100, (progress * 100).toInt(), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent())
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "キャンセル", cancelPi)
            .build()
    }

    /** 通知の進捗・タイトルを最新値で更新する。 */
    private fun notifyProgress(title: String, progress: Float) {
        if (!notificationsEnabled) return
        runCatching {
            notificationManager?.notify(
                NOTIFICATION_ID,
                buildProgressNotification("ダウンロード中", progress, title),
            )
        }
    }

    /** 完了通知 (スワイプで消せる)。 */
    private fun notifyCompletion(message: String) {
        if (!notificationsEnabled) return
        runCatching {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("ダウンロード完了")
                .setContentText(message)
                .setAutoCancel(true)
                .setContentIntent(contentPendingIntent())
                .build()
            notificationManager?.notify(NOTIFICATION_ID, notification)
        }
    }

    /** 失敗通知 (スワイプで消せる)。 */
    private fun notifyFailure(message: String) {
        if (!notificationsEnabled) return
        runCatching {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("ダウンロード失敗")
                .setContentText(message)
                .setAutoCancel(true)
                .setContentIntent(contentPendingIntent())
                .build()
            notificationManager?.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun contentPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** startForeground に渡すパラメータ (intent extras の入出力)。 */
    private data class DownloadParams(
        val stationId: String,
        val stationName: String,
        val programTitle: String,
        val ftEpochMillis: Long,
        val toEpochMillis: Long,
        val imgUrl: String?,
        val description: String?,
        val performer: String?,
        val areaId: String,
    ) {
        companion object {
            fun fromIntent(intent: Intent?): DownloadParams? {
                if (intent == null) return null
                val stationId = intent.getStringExtra(EXTRA_STATION_ID) ?: return null
                val stationName = intent.getStringExtra(EXTRA_STATION_NAME).orEmpty()
                val programTitle = intent.getStringExtra(EXTRA_PROGRAM_TITLE) ?: return null
                return DownloadParams(
                    stationId = stationId,
                    stationName = stationName,
                    programTitle = programTitle,
                    ftEpochMillis = intent.getLongExtra(EXTRA_FT_EPOCH, 0L),
                    toEpochMillis = intent.getLongExtra(EXTRA_TO_EPOCH, 0L),
                    imgUrl = intent.getStringExtra(EXTRA_IMG_URL),
                    description = intent.getStringExtra(EXTRA_DESCRIPTION),
                    performer = intent.getStringExtra(EXTRA_PERFORMER),
                    areaId = intent.getStringExtra(EXTRA_AREA_ID) ?: "JP13",
                )
            }
        }
    }

    companion object {
        private const val TAG = "DownloadService"

        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "download"
        const val ACTION_CANCEL = "com.radikk.app.action.CANCEL_DOWNLOAD"

        private const val EXTRA_STATION_ID = "stationId"
        private const val EXTRA_STATION_NAME = "stationName"
        private const val EXTRA_PROGRAM_TITLE = "programTitle"
        private const val EXTRA_FT_EPOCH = "ftEpochMillis"
        private const val EXTRA_TO_EPOCH = "toEpochMillis"
        private const val EXTRA_IMG_URL = "imgUrl"
        private const val EXTRA_DESCRIPTION = "description"
        private const val EXTRA_PERFORMER = "performer"
        private const val EXTRA_AREA_ID = "areaId"

        /**
         * ダウンロードを開始する (フォアグラウンドサービスとして起動)。
         * Android 8+ のバックグラウンド起動制限に対応するため
         * ContextCompat.startForegroundService を使う。
         */
        fun start(
            context: Context,
            stationId: String,
            stationName: String,
            programTitle: String,
            ftEpochMillis: Long,
            toEpochMillis: Long,
            imgUrl: String?,
            description: String?,
            performer: String?,
            areaId: String,
        ) {
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_STATION_ID, stationId)
                putExtra(EXTRA_STATION_NAME, stationName)
                putExtra(EXTRA_PROGRAM_TITLE, programTitle)
                putExtra(EXTRA_FT_EPOCH, ftEpochMillis)
                putExtra(EXTRA_TO_EPOCH, toEpochMillis)
                putExtra(EXTRA_IMG_URL, imgUrl)
                putExtra(EXTRA_DESCRIPTION, description)
                putExtra(EXTRA_PERFORMER, performer)
                putExtra(EXTRA_AREA_ID, areaId)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
