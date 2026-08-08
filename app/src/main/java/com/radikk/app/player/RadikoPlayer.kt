package com.radikk.app.player

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.AdtsExtractor
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.radikk.app.MainActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

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
 *    - medialist 直接指定では CODECS 属性が無いため、mime 判定ではなく `DefaultHlsExtractorFactory` の
 *      `AdtsExtractor.sniff()` が ID3 ヘッダーを読み飛ばして先頭 8KB 内の連続 ADTS フレームで検出する。
 *      セグメント URL が `.aac` 終端なので `inferFileTypeFromUri` により最優先で試される
 *    - 再生中も `AdtsReader` が各セグメントの ID3 をメタデータとして消費し、音声トラックには ADTS のみ出力する
 *    - Source Error 再発時は AdtsExtractor を明示登録するフォールバックを用意
 *
 * UnstableApi の opt-in は build.gradle.kts の compilerOptions (-opt-in) で解決している。
 */
class RadikoPlayer(
    context: Context,
) {
    /** サービス起動や通知用のアプリコンテキスト (Activity リーク防止のため applicationContext を使用) */
    private val appContext: Context = context.applicationContext

    companion object {
        private const val TAG = "RadikoPlayer"

        /** シークバー更新のポーリング間隔 */
        private const val POSITION_POLL_MS = 500L

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

    /**
     * ローカル .aac (ADTS) のシークを有効にするための Extractor フラグ。
     *
     * radiko のダウンロードファイルは複数の ADTS セグメントを単一の .aac に連結した
     * ストリームで、ファイルヘッダーに duration 情報が無い。Media3 の AdtsExtractor は
     * デフォルトでは UnseekableSeekMap を出力するため、ExoPlayer.seekTo しても位置 0 に
     * 戻ってしまいシークバーが機能しない (実機検証済み)。
     * FLAG_ENABLE_CONSTANT_BITRATE_SEEKING により「平均フレームサイズから算出した
     * 固定ビットレート」ベースのシークマップを出力し、シーク可能にする。
     * (ダウンロードは同一エンコード設定のセグメント連結なので CBR 近似でシーク精度は十分)
     */
    private val defaultExtractorsFactory: DefaultExtractorsFactory = DefaultExtractorsFactory()
        .setAdtsExtractorFlags(AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)

    private val _player = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(context, defaultExtractorsFactory))
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

    /** シークバー表示用の duration 上書き値 (タイムフリー: 番組全体の長さ)。null なら ExoPlayer の duration を使う。 */
    private var durationOverrideMs: Long? = null

    /** シークバー位置の再生アンカー (スライディングウィンドウ非依存の表示位置計算用) */
    private var playAnchorElapsed: Long = 0L
    private var playAnchorPositionMs: Long = 0L
    private var isPlayingState = false

    /**
     * ローカルファイル (ダウンロード済み .aac) 再生中かどうか。
     * true の間は ExoPlayer ネイティブの currentPosition / seekTo / duration をそのまま使い、
     * HLS スライディングウィンドウ用のアンカー計算を一切行わない。
     * (アンカー計算をローカルファイルに適用すると、シークしても表示位置が先頭に戻る不具合になる)
     */
    private var nativePosition = false

    private var dataSourceFactory: DefaultHttpDataSource.Factory? = null

    /** 位置ポーリング用スコープ (再生中に positionMs を定期的に更新する) */
    private val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null

    /** release() されたか (ポーリングが解放済み player にアクセスしないためのガード) */
    @Volatile
    private var released = false

    init {
        // メディア通知タップで MainActivity に戻るようにセッションアクティビティを設定する
        val sessionActivity = PendingIntent.getActivity(
            appContext,
            0,
            Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        mediaSession.setSessionActivity(sessionActivity)

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
                // エラー後も poll を確実に停止する (isPlaying=false で updateState が poll を止める)
                updateState()
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
     * @param durationOverrideMs シークバー表示用の duration 上書き値 (タイムフリー: 番組全体の長さ)。
     *                           null なら ExoPlayer の duration を使う。
     * @param startPositionMs 論理再生位置 (番組先頭からのミリ秒)。既定 0 = 番組先頭。
     *                        アンカーは常にこの位置から開始する。
     *
     * シーク後の再ロード (URL ベースシーク) では、サーバーが seek 位置から始まる
     * ~300s のスライディングウィンドウの medialist を返すため、メディア自体が seek 位置
     * から開始する。この場合 ExoPlayer への `seekTo` は不要で、行うとロード済みウィンドウの
     * 外を指して永久 BUFFERING に陥る可能性がある。よって `_player.seekTo` は
     * ロード済みウィンドウ内とみなせる小さいオフセット (1..300_000ms) のときだけ実行する。
     */
    fun playMedialist(medialistUrl: String, durationOverrideMs: Long? = null, startPositionMs: Long = 0L) {
        this.durationOverrideMs = durationOverrideMs
        this.nativePosition = false
        this.playAnchorElapsed = SystemClock.elapsedRealtime()
        this.playAnchorPositionMs = startPositionMs
        this.isPlayingState = false
        val factory = dataSourceFactory ?: run {
            Log.e(TAG, "setAuth() が呼ばれていない")
            return
        }
        val mediaItem = pendingMediaMetadata?.let {
            MediaItem.Builder().setUri(medialistUrl).setMediaMetadata(it).build()
        } ?: MediaItem.fromUri(medialistUrl)
        pendingMediaMetadata = null
        val hlsSource: MediaSource = HlsMediaSource.Factory(factory)
            .createMediaSource(mediaItem)

        _uiState.value = _uiState.value.copy(
            isLoading = true,
            error = null,
        )
        _player.setMediaSource(hlsSource)
        _player.prepare()
        // ウィンドウ基準のシーク (radiko はウィンドウ先頭が ft)。初期オフセットが
        // ロード済みウィンドウ内 (典型 ~300s) のときのみ実行する。ウィンドウ外の場合は
        // サーバーが seek 位置から開始する medialist を返すため ExoPlayer シークは不要。
        if (startPositionMs in 1..300_000L) {
            runCatching { _player.seekTo(startPositionMs) }
        }
        startPlaybackService()
        _player.play()
    }

    /**
     * ローカル音声ファイル (.aac 等) を直接再生する (ダウンロード済み番組用)。
     *
     * HLS (medialist) を経由しないため認証ヘッダーは不要。ローカルファイルは
     * スライディングウィンドウが無いため、位置・シーク・duration はすべて
     * ExoPlayer ネイティブの値をそのまま使う (アンカー計算は無効)。
     *
     * @param filePath ローカルファイルの絶対パス、または SAF (content://) の Uri 文字列
     * @param durationOverrideMs シークバー表示用の duration 上書き値 (番組全体の長さ)。
     *                           null なら ExoPlayer が実ファイルから duration を読む。
     */
    fun playLocalFile(filePath: String, durationOverrideMs: Long? = null) {
        this.durationOverrideMs = durationOverrideMs
        this.nativePosition = true
        // アンカーは HLS 専用だが、念のため無害な値にリセットしておく
        playAnchorElapsed = SystemClock.elapsedRealtime()
        playAnchorPositionMs = 0L
        isPlayingState = false
        val mediaItem = if (filePath.startsWith("content://")) {
            MediaItem.Builder().setUri(filePath).setMediaMetadata(pendingMediaMetadata ?: MediaMetadata.EMPTY).build()
        } else {
            MediaItem.Builder().setUri(Uri.fromFile(File(filePath))).setMediaMetadata(pendingMediaMetadata ?: MediaMetadata.EMPTY).build()
        }
        pendingMediaMetadata = null
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        _player.setMediaItem(mediaItem)
        _player.prepare()
        startPlaybackService()
        _player.play()
    }

    /** 一時停止 */
    fun pause() {
        if (!nativePosition && _player.isPlaying) {
            // 停止位置を確定してから pause
            playAnchorPositionMs = currentLogicalPosition()
        }
        isPlayingState = false
        _player.pause()
    }

    /** 再生再開 */
    fun play() {
        if (!nativePosition) {
            // 再開: アンカーを現在位置に再設定
            playAnchorPositionMs = currentLogicalPosition()
            playAnchorElapsed = SystemClock.elapsedRealtime()
        }
        isPlayingState = true
        _player.play()
    }

    /** シーク (タイムフリー用) */
    fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceAtLeast(0L)
        if (nativePosition) {
            // ローカルファイル: ExoPlayer ネイティブシーク。アンカー操作は不要。
            runCatching { _player.seekTo(clamped) }
            return
        }
        // ExoPlayer へのシークはウィンドウ基準 (radiko は ft がウィンドウ先頭)。
        // 論理位置はシーク後にアンカーを再設定して表現する。
        runCatching { _player.seekTo(clamped) }
        playAnchorPositionMs = clamped
        playAnchorElapsed = SystemClock.elapsedRealtime()
    }

    /** 現在の論理再生位置 (番組先頭からのミリ秒)。スライディングウィンドウ非依存。 */
    private fun currentLogicalPosition(): Long {
        if (nativePosition) {
            // ローカルファイルは ExoPlayer ネイティブ位置をそのまま使う (HLS のスライディングウィンドウ問題がない)
            return runCatching { _player.currentPosition }.getOrNull()?.coerceAtLeast(0L) ?: playAnchorPositionMs
        }
        if (isPlayingState) {
            return (playAnchorPositionMs + (SystemClock.elapsedRealtime() - playAnchorElapsed))
                .coerceAtLeast(0L)
        }
        return playAnchorPositionMs
    }

    /** 再生エラーをクリアする (Snackbar 表示後の消費用)。 */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /** 停止してリソース解放 */
    fun stop() {
        _player.stop()
    }

    fun release() {
        // 位置ポーリングを停止してから player を解放する
        // (解放済み ExoPlayer へのアクセスで IllegalStateException になるのを防ぐ)
        released = true
        pollJob?.cancel()
        pollJob = null
        pollScope.cancel()
        stopPlaybackService()
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

    /**
     * 再生開始時に PlaybackService へ MediaController を接続する。
     *
     * Media3 の MediaSessionService は、MediaController がサービスへ接続した際に
     * onGetSession → addSession の流れでセッションを登録し、MediaNotificationManager が
     * メディア通知の表示と startForeground() (mediaPlayback FGS) を自動処理する。
     * 本アプリは共有 MediaSession を直接操作しているため、この接続が FGS 化と
     * バックグラウンド再生継続のトリガーになる。
     */
    private fun startPlaybackService() {
        if (PlaybackService.sharedMediaSession === null) return
        // 二重接続防止 (release で切断してから再接続する)
        if (serviceController != null) return
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        serviceController = future
        future.addListener(
            {
                val controller = runCatching { future.get() }.getOrNull()
                if (controller != null) {
                    Log.d(TAG, "PlaybackService に MediaController 接続完了")
                } else {
                    serviceController = null
                }
            },
            controllerListenerExecutor
        )
    }

    /**
     * PlaybackService の MediaController を切断し、サービスを停止可能にする。
     * 再生終了時に呼ぶ。FGS は Media3 が再生終了を検知して自ら降格・停止する。
     */
    fun stopPlaybackService() {
        serviceController?.let { MediaController.releaseFuture(it) }
        serviceController = null
    }

    /** PlaybackService 用の MediaController (メディア通知 + FGS のトリガー) */
    private var serviceController: ListenableFuture<MediaController>? = null

    /** MediaController 接続完了リスナーの実行用 (メインスレッドでUIと整合させる) */
    private val controllerListenerExecutor: Executor = ContextCompat.getMainExecutor(appContext)

    /**
     * 次回 playMedialist/playLocalFile の MediaItem に設定するメタデータ。
     * メディア通知に番組名・局名を表示するために使う。
     * 再生開始前に呼び、次の play で消費される (一度使うとクリアされる)。
     */
    private var pendingMediaMetadata: MediaMetadata? = null

    /** メディア通知に表示する番組情報を、次回再生の MediaItem に設定する */
    fun setMediaMetadata(title: String, artist: String) {
        pendingMediaMetadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .build()
    }

    /** 表示用の duration を安全に計算する (C.TIME_UNSET など無効値は 0 に丸める)。 */
    private fun effectiveDuration(): Long {
        if (nativePosition) {
            // ローカルファイル: 実ファイルの duration を優先する (truncated ダウンロードは短い実長を表示)
            val d = runCatching { _player.duration }.getOrNull() ?: 0L
            if (d > 0) return d
        }
        durationOverrideMs?.let { if (it > 0) return it }
        val d = _player.duration
        return if (d > 0) d else 0L
    }

    private fun updateState() {
        // release() 後にリスナーから呼ばれることがあるため、解放済み player にアクセスしない
        if (released) return
        val isPlaying = _player.isPlaying
        val isReady = _player.playbackState == Player.STATE_READY
        // 再生へ遷移した直後はアンカーを現在時刻で再設定する (スライディングウィンドウ非依存)。
        // ローカルファイルは ExoPlayer ネイティブ位置を使うためアンカー再設定は不要。
        if (isPlaying && !isPlayingState && !nativePosition) {
            playAnchorElapsed = SystemClock.elapsedRealtime()
        }
        isPlayingState = isPlaying
        _uiState.value = PlayerUiState(
            isPlaying = isPlaying,
            isLoading = _player.playbackState == Player.STATE_BUFFERING || _player.playbackState == Player.STATE_IDLE,
            isBuffering = _player.playbackState == Player.STATE_BUFFERING,
            isReady = isReady,
            error = _uiState.value.error,
            positionMs = currentLogicalPosition(),
            durationMs = effectiveDuration(),
        )

        // 再生中は位置をポーリングしてシークバーを進める
        if (isPlaying && !released) {
            if (pollJob == null || pollJob?.isActive != true) {
                pollJob = pollScope.launch {
                    while (isActive && !released) {
                        runCatching {
                            _uiState.value = _uiState.value.copy(
                                positionMs = currentLogicalPosition(),
                                durationMs = effectiveDuration(),
                            )
                        }
                        delay(POSITION_POLL_MS)
                    }
                }
            }
        } else {
            pollJob?.cancel()
            pollJob = null
        }
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
