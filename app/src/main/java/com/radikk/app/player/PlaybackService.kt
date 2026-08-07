package com.radikk.app.player

import android.app.PendingIntent
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
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "PlaybackService created")
    }

    /**
     * 接続要求に対し、アプリ共有の MediaSession を返す。
     * アプリが生きている間は RadikoPlayer.mediaSession が設定されている。
     */
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        sharedMediaSession

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
    }
}
