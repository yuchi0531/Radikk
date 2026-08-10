package com.radikk.app.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.radikk.app.MainActivity

/**
 * Media3 MediaSessionService。
 *
 * バックグラウンド再生が有効なときに、再生をフォアグラウンドサービスとして継続する。
 *
 * 設計:
 * - RadikoPlayer がアプリ全体の共有プレイヤー + MediaSession を持つ
 * - この PlaybackService は、バックグラウンド再生 ON 時に
 *   アプリがバックグラウンドへ移っても再生を継続するための FGS ホスト
 * - MediaSessionService のコントローラー接続に対応するため、RadikoPlayer の
 *   MediaSession を返す (onGetSession をオーバーライド)
 */
class PlaybackService : MediaSessionService() {

    companion object {
        private const val TAG = "PlaybackService"

        /** RadikoPlayer の MediaSession をサービス経由で公開するための実装 */
        @Volatile
        var sharedMediaSession: MediaSession? = null

        /**
         * 前回プロセスに残ったバックグラウンド再生 (FGS) をクリーンに停止する。
         *
         * スワイプアウェイ後にバックグラウンド再生が継続したままアプリを再起動すると、
         * [sharedMediaSession] に古い MediaSession が残っている。このまま新しい
         * RadikoPlayer が MediaSession を生成すると Media3 の静的
         * SESSION_ID_TO_SESSION_MAP で ID 衝突 (Session ID must be unique) が起きる。
         *
         * 新しい RadikoPlayer の生成前に呼び出し、旧プレイヤーを停止・解放してから
         * 共有セッションを破棄・サービスを停止することで、再起動時は常にまっさらな
         * 状態から再生を始められる。バックグラウンド再生が無い場合は何もしない。
         *
         * @param context サービス停止に使うコンテキスト (呼び出し側の applicationContext を推奨)
         */
        fun stopBackgroundPlayback(context: Context) {
            val session = sharedMediaSession ?: return
            sharedMediaSession = null
            val player = session.player
            // 1. 旧プレイヤーの再生を停止する
            try {
                if (player.playWhenReady) player.stop()
            } catch (e: Exception) {
                Log.w(TAG, "旧プレイヤーの停止に失敗: ${e.message}")
            }
            // 2. 旧 MediaSession を解放する (コントローラー切断・静的マップから除去)
            try {
                session.release()
            } catch (e: Exception) {
                Log.w(TAG, "旧 MediaSession の解放に失敗: ${e.message}")
            }
            // 3. ExoPlayer も解放してリソースを返す (releaseForBackground は player を残すため)
            try {
                player.release()
            } catch (e: Exception) {
                Log.w(TAG, "旧プレイヤーの解放に失敗: ${e.message}")
            }
            // 4. プロセスが生きている間に FGS を明示的に終了する (サービス自身からだと onDestroy が走る)
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "PlaybackService created")
    }

    /**
     * 接続要求に対し、アプリ共有の MediaSession を返す。
     * アプリが生きている間は RadikoPlayer.mediaSession が設定されている。
     */
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        val session = sharedMediaSession
        // このインスタンスがホストしたセッションを記録し、onDestroy で
        // 「自分がホストしていたもの」だけ sharedMediaSession から外す
        if (session != null) hostedSession = session
        return session
    }

    /**
     * RadikoPlayer が startForegroundService で明示的に起動するため、
     * 再起動時も再生を継続できるよう START_STICKY を返す。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val session = sharedMediaSession
        val shouldStop = session?.player?.playbackState == Player.STATE_ENDED ||
            session?.player?.playWhenReady == false
        if (shouldStop) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "PlaybackService destroyed")
        // プロセス生存中は RadikoPlayer.release() が共有セッションをクリアするが、
        // プロセス死 (タスクのスワイプアウェイで FGS ごと破棄される場合など) では
        // 解放済み MediaSession が残留しうる。ここで確実に null にしておく。
        // ただし「自分がホストしていたセッション」に限定する。再起動時に新しい
        // RadikoPlayer が sharedMediaSession を差し替えた直後に旧サービスの
        // onDestroy が走ると、新しいセッションまで誤って消してしまうため
        // (実機検証で FGS 通知が消える不具合を確認)。
        val hosted = this.hostedSession
        this.hostedSession = null
        if (hosted !== null && sharedMediaSession === hosted) {
            sharedMediaSession = null
        }
    }

    /** このサービスインスタンスが onGetSession で返したセッション (onDestroy での後片付け用) */
    private var hostedSession: MediaSession? = null
}
