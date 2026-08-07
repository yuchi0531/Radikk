package com.radikk.app.player

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.session.MediaSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * radiko 再生エンジン (Media3/ExoPlayer ラッパー)。
 *
 * ## HLS Source Error 回避策 (Flutter 版の失敗を踏まえて)
 * 1. **medialist URL を直接 MediaItem に渡す** (マスタープレイリストを介さない)
 *    - m3u8 はアプリ側で取得し、#EXT-X-STREAM-INF 直後の medialist URL を抽出
 *    - これによりマスタープレイリストのバリアント選択処理をスキップ
 * 2. **DefaultHttpDataSource.Factory に認証ヘッダーを設定**
 *    - setDefaultRequestProperties は HLS の全リクエストに適用される
 * 3. **ID3 タグ付き ADTS AAC セグメントの処理**
 *    - radiko の .aac セグメントは「ID3v2 タグ + ADTS」で Content-Type は application/octet-stream
 *    - Media3 の HLS パーサーは CODECS="mp4a.40.5" から audio/mp4a-latm と判断して ADTS 抽出する
 *    - Source Error 再発時は AdtsExtractor を明示登録するフォールバックを用意
 *
 * UnstableApi の opt-in は build.gradle.kts の compilerOptions (-opt-in) で解決している。
 */
class RadikoPlayer(
    context: Context,
) {
    companion object {
        private const val TAG = "RadikoPlayer"

        /** 検証済みの UA (radiko 公式アプリと同等) */
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; Pixel 4 XL) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/80.0.3987.87 Mobile Safari/537.36"
    }

    /** 再生エラー種別 */
    sealed class PlayerError(val message: String) {
        /** HLS Source Error (マスタープレイリストや抽出処理の問題) */
        class SourceError(details: String) : PlayerError("再生ストリームの解析に失敗しました ($details)")
        /** 認証エラー */
        class AuthError(details: String) : PlayerError("認証エラー ($details)")
        /** ネットワークエラー */
        class NetworkError(details: String) : PlayerError("ネットワークエラー ($details)")
        /** 不明なエラー */
        class Unknown(details: String) : PlayerError("再生エラー ($details)")
    }

    private val _player = ExoPlayer.Builder(context)
        .build()

    val player: ExoPlayer = _player

    /** MediaSession (バックグラウンド再生・メディア通知用) */
    val mediaSession: MediaSession = MediaSession.Builder(context, _player).build()

    /** 現在の再生状態 (UI 連携用) */
    data class PlayerUiState(
        val isPlaying: Boolean = false,
        val isLoading: Boolean = false,
        val isBuffering: Boolean = false,
        val isReady: Boolean = false,
        val error: PlayerError? = null,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
    )

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    /** 認証ヘッダー (AreaId 変更・トークン更新で設定し直す) */
    private var authToken: String? = null
    private var areaId: String? = null

    private var dataSourceFactory: DefaultHttpDataSource.Factory? = null

    init {
        _player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateState()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updateState()
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "onPlayerError: errorCode=${error.errorCode}, cause=${error.cause}")
                Log.e(TAG, "  message=${error.message}")
                val classified = classifyError(error)
                _uiState.value = _uiState.value.copy(error = classified, isPlaying = false, isLoading = false)
            }
        })
        applyAudioAttributes()
    }

    /** 認証情報を設定する。ストリーム再生前に必ず呼ぶ。 */
    fun setAuth(token: String, areaId: String) {
        this.authToken = token
        this.areaId = areaId
        // 認証ヘッダーを HLS の全リクエストに適用する
        val factory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(
                mapOf(
                    "X-Radiko-AuthToken" to token,
                    "X-Radiko-AreaId" to areaId,
                )
            )
        this.dataSourceFactory = factory
    }

    /**
     * medialist URL を直接再生する。
     * @param medialistUrl medialist URL (マスタープレイリストではない)
     */
    fun playMedialist(medialistUrl: String) {
        val factory = dataSourceFactory ?: run {
            Log.e(TAG, "setAuth() が呼ばれていない")
            return
        }
        val mediaItem = MediaItem.fromUri(medialistUrl)
        val hlsSource: MediaSource = HlsMediaSource.Factory(factory)
            .createMediaSource(mediaItem)

        _uiState.value = _uiState.value.copy(
            isLoading = true,
            error = null,
        )
        _player.setMediaSource(hlsSource)
        _player.prepare()
        _player.play()
    }

    /** 一時停止 */
    fun pause() {
        _player.pause()
    }

    /** 再生再開 */
    fun play() {
        _player.play()
    }

    /** シーク (タイムフリー用) */
    fun seekTo(positionMs: Long) {
        _player.seekTo(positionMs)
    }

    /** 停止してリソース解放 */
    fun stop() {
        _player.stop()
    }

    fun release() {
        mediaSession.release()
        _player.release()
    }

    private fun applyAudioAttributes() {
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        _player.setAudioAttributes(attrs, /* handleAudioFocus = */ true)
    }

    /** バックグラウンド再生 OFF 時: オーディオフォーカスを外す */
    fun setHandleAudioFocus(handle: Boolean) {
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        _player.setAudioAttributes(attrs, handle)
    }

    private fun updateState() {
        _uiState.value = PlayerUiState(
            isPlaying = _player.isPlaying,
            isLoading = _player.playbackState == Player.STATE_BUFFERING || _player.playbackState == Player.STATE_IDLE,
            isBuffering = _player.playbackState == Player.STATE_BUFFERING,
            isReady = _player.playbackState == Player.STATE_READY,
            error = _uiState.value.error,
            positionMs = _player.currentPosition,
            durationMs = _player.duration,
        )
    }

    /** PlaybackException を分類する */
    private fun classifyError(error: PlaybackException): PlayerError {
        val cause = error.cause
        val msg = error.message ?: ""
        return when {
            // 認証エラー (401) — Source Error 分類より先にチェックする
            msg.contains("401") || msg.contains("missing token") || msg.contains("403") ->
                PlayerError.AuthError(msg.take(120))
            // HLS Source Error / 抽出エラー
            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                msg.contains("Source Error") ||
                msg.contains("SampleQueueMapping") ->
                PlayerError.SourceError(msg.take(120))
            // ネットワーク
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                cause is java.io.IOException ->
                PlayerError.NetworkError(msg.take(120))
            else -> PlayerError.Unknown(msg.take(120))
        }
    }
}
