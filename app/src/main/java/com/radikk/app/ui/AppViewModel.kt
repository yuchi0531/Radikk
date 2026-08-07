package com.radikk.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.radikk.app.RadikkApplication
import com.radikk.app.data.datastore.AppSettings
import com.radikk.app.data.datastore.ThemeMode
import com.radikk.app.data.model.AuthSession
import com.radikk.app.data.model.Program
import com.radikk.app.data.model.Station
import com.radikk.app.player.PlaybackService
import com.radikk.app.player.RadikoPlayer
import com.radikk.app.player.StreamUrlResolver
import com.radikk.app.util.RadikoTimeUtil
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * アプリ全体の ViewModel。
 * 認証・局一覧・再生状態を管理し、各画面 (ライブ/番組表/タイムフリー/設定) に共有する。
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as RadikkApplication
    private val settings = app.settingsRepository
    private val auth = app.authRepository
    private val stationRepo = app.stationRepository
    private val programRepo = app.programRepository

    val radikoPlayer = RadikoPlayer(application)

    // --- 設定状態 ---
    private val _settings = MutableStateFlow(AppSettings())
    val settingsFlow: StateFlow<AppSettings> = _settings.asStateFlow()

    // --- エリア ---
    private val _selectedAreaId = MutableStateFlow("JP13")
    val selectedAreaId: StateFlow<String> = _selectedAreaId.asStateFlow()

    // --- 局一覧 ---
    sealed class StationUiState {
        object Loading : StationUiState()
        data class Success(val stations: List<Station>) : StationUiState()
        data class Error(val message: String) : StationUiState()
    }

    private val _stationState = MutableStateFlow<StationUiState>(StationUiState.Loading)
    val stationState: StateFlow<StationUiState> = _stationState.asStateFlow()

    // --- 認証状態 ---
    sealed class AuthUiState {
        object Loading : AuthUiState()
        data class Success(val session: AuthSession) : AuthUiState()
        data class Error(val message: String) : AuthUiState()
    }

    private val _authState = MutableStateFlow<AuthUiState>(AuthUiState.Loading)
    val authState: StateFlow<AuthUiState> = _authState.asStateFlow()

    // --- 再生状態 ---
    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    data class NowPlaying(
        val stationId: String,
        val stationName: String,
        val title: String,
        val isTimefree: Boolean,
    )

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val playerUiState = radikoPlayer.uiState

    init {
        // PlaybackService に共有 MediaSession を公開 (バックグラウンド再生用)
        PlaybackService.sharedMediaSession = radikoPlayer.mediaSession

        viewModelScope.launch {
            settings.settings.collect { s ->
                val prevArea = _selectedAreaId.value
                _settings.value = s
                _selectedAreaId.value = s.areaId
                // バックグラウンド再生設定の反映 (AudioFocus 制御)
                applyBackgroundPlayback(s.backgroundPlayback)
                // エリア変更時のみ再認証 (初回ロード含む)。設定コレクターと changeArea の並走は
                // AuthRepository の Mutex シングルフライトで吸収される
                if (s.areaId.isNotEmpty() && (prevArea != s.areaId || _authState.value is AuthUiState.Loading)) {
                    refreshAuthIfNeeded(s.areaId)
                }
            }
        }

        viewModelScope.launch {
            loadStations()
        }
    }

    /**
     * バックグラウンド再生設定を反映する。
     *
     * Media3 の MediaSession は再生中に自動で通知 + フォアグラウンドサービスを維持するため、
     * ここでは明示的に startForegroundService を呼ばない (呼ぶと
     * ForegroundServiceDidNotStartInTimeException でクラッシュする)。
     *
     * - ON: メディア通知が再生中に表示され、バックグラウンドでも継続する
     * - OFF: メディア通知を非表示にし、オーディオフォーカスを要求しない
     *        (アプリがバックグラウンドに移ると再生が止まる)
     */
    private fun applyBackgroundPlayback(enabled: Boolean) {
        // メディア通知 (MediaSession) の表示/非表示を制御する。
        // Media3 の MediaSession はデフォルトで再生中に通知を出す。
        // バックグラウンド再生 OFF 時は、通知を隠すために playbackState を
        // 監視して停止させる。ここでは AudioFocus 制御のみ行う。
        radikoPlayer.setHandleAudioFocus(!enabled)
    }

    private suspend fun refreshAuthIfNeeded(areaId: String) {
        _authState.value = AuthUiState.Loading
        _errorMessage.value = null
        try {
            val session = auth.getSession(areaId)
            _authState.value = AuthUiState.Success(session)
            radikoPlayer.setAuth(session.token, session.areaId)
        } catch (e: Exception) {
            _authState.value = AuthUiState.Error(e.message ?: "認証に失敗しました")
        }
    }

    /** エリアを変更する (エリア選択時)。 */
    fun changeArea(areaId: String) {
        if (_selectedAreaId.value == areaId) return
        viewModelScope.launch {
            // setAreaId すると settings コレクターが再認証を実行する
            // (changeArea 側では refreshSession しない — 二重認証を防ぐ)
            settings.setAreaId(areaId)
            _selectedAreaId.value = areaId
            loadStations()
        }
    }

    /** 現在のエリアで聴ける局を読み込む。 */
    private suspend fun loadStations() {
        _stationState.value = StationUiState.Loading
        try {
            val all = stationRepo.getStations()
            val areaId = _selectedAreaId.value
            val filtered = stationRepo.filterByArea(all, areaId)
            _stationState.value = StationUiState.Success(filtered)
        } catch (e: Exception) {
            _stationState.value = StationUiState.Error(e.message ?: "放送局一覧の取得に失敗しました")
        }
    }

    /** ライブ再生を開始する。 */
    fun playLive(station: Station) {
        viewModelScope.launch {
            _errorMessage.value = null
            try {
                // バックグラウンド再生設定に応じて FGS を起動/停止
                applyBackgroundPlayback(_settings.value.backgroundPlayback)

                val session = auth.getSession(_selectedAreaId.value)
                radikoPlayer.setAuth(session.token, session.areaId)

                val resolver = StreamUrlResolver(app.apiClient)
                val medialistUrl = resolver.resolveLiveMedialistUrl(station.id, session.token)
                radikoPlayer.playMedialist(medialistUrl)

                _nowPlaying.value = NowPlaying(
                    stationId = station.id,
                    stationName = station.name,
                    title = "ライブ放送",
                    isTimefree = false,
                )
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "ライブ再生を開始できませんでした"
            }
        }
    }

    /** タイムフリー再生を開始する。 */
    fun playTimefree(station: Station, program: Program) {
        viewModelScope.launch {
            _errorMessage.value = null
            try {
                // バックグラウンド再生設定に応じて FGS を起動/停止
                applyBackgroundPlayback(_settings.value.backgroundPlayback)

                val session = auth.getSession(_selectedAreaId.value)
                radikoPlayer.setAuth(session.token, session.areaId)

                val resolver = StreamUrlResolver(app.apiClient)
                val medialistUrl = resolver.resolveTimefreeMedialistUrl(
                    stationId = station.id,
                    token = session.token,
                    from = program.ft,
                    to = program.to,
                )
                radikoPlayer.playMedialist(medialistUrl)

                _nowPlaying.value = NowPlaying(
                    stationId = station.id,
                    stationName = station.name,
                    title = program.title,
                    isTimefree = true,
                )
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "タイムフリー再生を開始できませんでした"
            }
        }
    }

    /** 番組表を取得する。 */
    suspend fun getPrograms(stationId: String, dayOffset: Int = 0): List<Program> {
        val dayStart = RadikoTimeUtil.todayDayStart().plusSeconds(dayOffset * 24 * 3600L)
        val apiDate = RadikoTimeUtil.apiDateFor(dayStart)
        return programRepo.getPrograms(stationId, apiDate)
    }

    /**
     * 複数局の番組表を並列取得する (EPG グリッド用)。
     * @return 局ID → 番組リスト (失敗した局は空リスト)
     */
    suspend fun getProgramsForStations(stations: List<Station>, dayOffset: Int): Map<String, List<Program>> =
        coroutineScope {
            val dayStart = RadikoTimeUtil.todayDayStart().plusSeconds(dayOffset * 24 * 3600L)
            val apiDate = RadikoTimeUtil.apiDateFor(dayStart)
            stations.map { station ->
                async {
                    try {
                        station.id to programRepo.getPrograms(station.id, apiDate)
                    } catch (e: Exception) {
                        station.id to emptyList<Program>()
                    }
                }
            }.associate { it.await() }
        }

    /** 再生エラーを UI に反映する。 */
    fun consumePlayerError(): String? {
        val err = playerUiState.value.error
        _errorMessage.value = err?.message
        return err?.message
    }

    /** 一時停止 */
    fun pause() = radikoPlayer.pause()

    /** 再生再開 */
    fun play() = radikoPlayer.play()

    /** シーク */
    fun seekTo(ms: Long) = radikoPlayer.seekTo(ms)

    /** テーマ設定 */
    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settings.setDynamicColor(enabled) }
    }

    fun setBackgroundPlayback(enabled: Boolean) {
        viewModelScope.launch { settings.setBackgroundPlayback(enabled) }
    }

    /** 認証キャッシュ削除 */
    fun clearAuthCache() {
        viewModelScope.launch {
            settings.clearAuth()
            _authState.value = AuthUiState.Loading
            try {
                val session = auth.refreshSession(_selectedAreaId.value)
                _authState.value = AuthUiState.Success(session)
                radikoPlayer.setAuth(session.token, session.areaId)
            } catch (e: Exception) {
                _authState.value = AuthUiState.Error(e.message ?: "再認証に失敗しました")
            }
        }
    }

    /** 現在再生中か */
    val isPlaying: Boolean
        get() = radikoPlayer.player.isPlaying

    override fun onCleared() {
        radikoPlayer.release()
        super.onCleared()
    }
}
