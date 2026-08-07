package com.radikk.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.radikk.app.RadikkApplication
import com.radikk.app.data.datastore.AppSettings
import com.radikk.app.data.datastore.ThemeMode
import com.radikk.app.data.history.HistoryEntry
import com.radikk.app.data.history.HistoryRepository
import com.radikk.app.data.model.AuthSession
import com.radikk.app.data.model.Program
import com.radikk.app.data.model.Station
import com.radikk.app.data.programcache.ProgramCacheRepository
import com.radikk.app.data.reminder.ReminderRepository
import com.radikk.app.data.reminder.ReminderScheduler
import com.radikk.app.data.reminder.StoredReminder
import com.radikk.app.data.timefree.CachedTimefreeProgram
import com.radikk.app.data.timefree.TimefreeCacheRepository
import com.radikk.app.player.PlaybackService
import com.radikk.app.player.RadikoPlayer
import com.radikk.app.player.StreamUrlResolver
import com.radikk.app.util.RadikoTimeUtil
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
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
    private val reminderRepo = app.reminderRepository
    private val timefreeCache = app.timefreeCacheRepository
    private val programCache = app.programCacheRepository
    private val historyRepo = app.historyRepository

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
        val stationLogoUrl: String? = null,
        val programImgUrl: String? = null,
        val description: String? = null,
        val performer: String? = null,
    )

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val playerUiState = radikoPlayer.uiState

    /**
     * 現在のタイムフリー再生コンテキスト。シーク時にプレイリストを
     * `seek` パラメータ付きで作り直すために保持する。ライブ再生や停止でクリアされる。
     */
    private var timefreeContext: TimefreeSeekContext? = null

    private data class TimefreeSeekContext(
        val station: Station,
        val ft: Instant,
        val to: Instant,
        val token: String,
    )

    // --- 番組開始通知 (リマインダー) ---
    private val _reminders = MutableStateFlow<List<StoredReminder>>(emptyList())
    val reminders: StateFlow<List<StoredReminder>> = _reminders.asStateFlow()

    // --- 聞いた番組の履歴 ---
    val history: StateFlow<List<HistoryEntry>> = historyRepo.history.stateIn(
        viewModelScope, SharingStarted.Eagerly, emptyList()
    )

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

        // リマインダー一覧を監視し、通知設定を反映する
        viewModelScope.launch {
            reminderRepo.reminders.collect { list ->
                _reminders.value = list
            }
        }

        // アプリ起動時に保存済みリマインダーを AlarmManager へ再スケジュール
        viewModelScope.launch {
            val scheduler = ReminderScheduler(app)
            scheduler.rescheduleAll(reminderRepo.currentReminders())
        }

        // 起動時に選択エリア全局の「今日分」番組表を一括プリロード (一日一回)
        viewModelScope.launch {
            preloadTodayPrograms()
        }
    }

    /**
     * 選択エリア全局の「今日分」番組表を並列取得して永続キャッシュに保存する。
     * 今日すでに取得済みのエリア・日付はスキップする (一日一回制御)。
     */
    private suspend fun preloadTodayPrograms() {
        runCatching {
            // 保存済み設定 (エリア) が反映されるのを待つ。
            // 初期値は JP13 のため、設定読み込み前に走ると保存済みエリアと不一致になる。
            val areaId = settings.currentSettings().areaId.ifEmpty { _selectedAreaId.value }
            val apiDate = RadikoTimeUtil.apiDateFor(RadikoTimeUtil.todayDayStart())
            if (programCache.isFetchedToday(areaId, apiDate)) return@runCatching

            // 局一覧のロードを待つ
            val stations = stationRepo.getStations().let { all ->
                stationRepo.filterByArea(all, areaId)
            }
            if (stations.isEmpty()) return@runCatching

            // 全局を並列取得してキャッシュへ保存
            val programsByStation = coroutineScope {
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
            programCache.putPrograms(areaId, apiDate, programsByStation)
        }
    }

    /**
     * 指定日付の全局番組表を返す。永続キャッシュに「今日分」がなければ取得して保存する。
     * EPG 番組表で使う (キャッシュ優先で高速化)。
     */
    suspend fun getProgramsForStationsWithCache(
        stations: List<Station>,
        dayOffset: Int,
    ): Map<String, List<Program>> {
        val areaId = _selectedAreaId.value
        val dayStart = RadikoTimeUtil.todayDayStart().plusSeconds(dayOffset * 24 * 3600L)
        val apiDate = RadikoTimeUtil.apiDateFor(dayStart)

        // 今日分なら永続キャッシュを確認 (要求された全局がキャッシュ済みの場合のみ使用)
        val today = RadikoTimeUtil.apiDateFor(RadikoTimeUtil.todayDayStart())
        if (apiDate == today && programCache.isFetchedToday(areaId, apiDate)) {
            val cached = programCache.getPrograms(areaId, apiDate)
            // キャッシュが要求された全局をカバーしている場合のみ使用する
            // (一部欠けている場合は全取得して欠落を補う)
            val coversAll = stations.all { cached.containsKey(it.id) }
            if (coversAll) return cached
        }

        // キャッシュなし → 全局並列取得して保存
        val programsByStation = coroutineScope {
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
        if (apiDate == today) {
            programCache.putPrograms(areaId, apiDate, programsByStation)
        }
        return programsByStation
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
            // 新エリアの「今日分」番組表をプリロードする (検索・番組表を即時利用可能に)
            preloadTodayPrograms()
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
            // ライブは URL ベースシーク対象外 (タイムフリーのみ)
            timefreeContext = null
            try {
                // バックグラウンド再生設定に応じて FGS を起動/停止
                applyBackgroundPlayback(_settings.value.backgroundPlayback)

                val session = auth.getSession(_selectedAreaId.value)
                radikoPlayer.setAuth(session.token, session.areaId)

                val resolver = StreamUrlResolver(app.apiClient)
                val medialistUrl = resolver.resolveLiveMedialistUrl(station.id, session.token)
                radikoPlayer.playMedialist(medialistUrl)

                // ライブ再生中は現在の番組名を番組表から随時更新して表示する
                val titleUsed = loadCurrentProgramTitle(station.id)
                _nowPlaying.value = NowPlaying(
                    stationId = station.id,
                    stationName = station.name,
                    title = titleUsed ?: "ライブ放送",
                    isTimefree = false,
                    stationLogoUrl = station.logoUrl,
                )
                historyRepo.add(HistoryEntry(
                    stationId = station.id,
                    stationName = station.name,
                    programTitle = titleUsed ?: station.name,
                    isTimefree = false,
                    listenedAtEpochMillis = Instant.now().toEpochMilli(),
                ))
                startLiveTitleRefresher(station)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "ライブ再生を開始できませんでした"
            }
        }
    }

    /**
     * 現在放送中の番組タイトルを番組表キャッシュから取得する。
     * 取得に失敗した場合や放送中番組が見つからない場合は null を返す。
     *
     * 注意: ここではキャッシュを保存しない (programCache.putPrograms は全局置換のため、
     * 1局分の保存が他の局のキャッシュを消してしまう)。
     */
    private suspend fun loadCurrentProgramTitle(stationId: String): String? = runCatching {
        val areaId = _selectedAreaId.value
        val apiDate = RadikoTimeUtil.apiDateFor(RadikoTimeUtil.todayDayStart())
        // 今日分の番組表を取得 (キャッシュ優先)
        val cached = programCache.getPrograms(areaId, apiDate)[stationId].orEmpty()
        val programs = if (cached.isNotEmpty()) {
            cached
        } else {
            // キャッシュにない場合はネットワーク取得 (保存はしない)
            runCatching { programRepo.getPrograms(stationId, apiDate) }.getOrDefault(emptyList())
        }
        programs
    }.getOrNull()
        ?.firstOrNull { RadikoTimeUtil.isOnAir(it.ft, it.to) }
        ?.let { it.title }
        ?.takeIf { it.isNotBlank() }

    /**
     * ライブ再生中の番組名を定期的に更新する。
     * 番組切替 (ft/to) に追従するため 30 秒ごとに現在放送中番組を再取得する。
     * 再生状態がライブでなくなったら停止する。
     */
    private var liveTitleJob: kotlinx.coroutines.Job? = null

    private fun startLiveTitleRefresher(station: Station) {
        liveTitleJob?.cancel()
        liveTitleJob = viewModelScope.launch {
            while (isActive) {
                delay(30_000)
                // 再生中でない・ライブでない場合は停止
                val np = _nowPlaying.value ?: break
                if (np.isTimefree || np.stationId != station.id) break
                if (!radikoPlayer.uiState.value.isPlaying) break
                val title = loadCurrentProgramTitle(station.id)
                if (title != null && title != _nowPlaying.value?.title) {
                    _nowPlaying.value = _nowPlaying.value?.copy(title = title)
                }
            }
            liveTitleJob = null
        }
    }

    /** 再生を停止する。 */
    fun stop() {
        radikoPlayer.stop()
        timefreeContext = null
        liveTitleJob?.cancel()
        liveTitleJob = null
        _nowPlaying.value = null
    }

    /** タイムフリー再生を開始する。 */
    fun playTimefree(station: Station, program: Program) {
        // ライブ用タイトル更新を停止する (タイムフリーは固定の番組名)
        liveTitleJob?.cancel()
        liveTitleJob = null
        viewModelScope.launch {
            _errorMessage.value = null
            try {
                // バックグラウンド再生設定に応じて FGS を起動/停止
                applyBackgroundPlayback(_settings.value.backgroundPlayback)

                val session = auth.getSession(_selectedAreaId.value)
                radikoPlayer.setAuth(session.token, session.areaId)
                // シーク時のプレイリスト再構築用に現在のタイムフリー再生コンテキストを保持する
                timefreeContext = TimefreeSeekContext(station, program.ft, program.to, session.token)

                val resolver = StreamUrlResolver(app.apiClient)
                val medialistUrl = resolver.resolveTimefreeMedialistUrl(
                    stationId = station.id,
                    token = session.token,
                    from = program.ft,
                    to = program.to,
                )
                val durationMs = (program.to.toEpochMilli() - program.ft.toEpochMilli()).coerceAtLeast(0L)
                radikoPlayer.playMedialist(medialistUrl, durationOverrideMs = durationMs)

                _nowPlaying.value = NowPlaying(
                    stationId = station.id,
                    stationName = station.name,
                    title = program.title,
                    isTimefree = true,
                    stationLogoUrl = station.logoUrl,
                    programImgUrl = program.imgUrl,
                    description = program.description,
                    performer = program.performer,
                )
                historyRepo.add(HistoryEntry(
                    stationId = station.id,
                    stationName = station.name,
                    programTitle = program.title,
                    isTimefree = true,
                    listenedAtEpochMillis = Instant.now().toEpochMilli(),
                ))
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "タイムフリー再生を開始できませんでした"
            }
        }
    }

    /**
     * 通知タップからの再生を開始する。
     * 通知は放送開始時刻に発火するため、その局をライブ再生する。
     * 局が一覧に見つからない場合はタイムフリー (放送中の番組) を試みる。
     */
    fun playFromReminder(
        stationId: String,
        stationName: String,
        programTitle: String,
        startEpochMillis: Long,
        endEpochMillis: Long,
    ) {
        viewModelScope.launch {
            _errorMessage.value = null
            try {
                // 局一覧から該当局を探す
                val all = stationRepo.getStations()
                val station = all.firstOrNull { it.id == stationId }
                if (station == null) {
                    _errorMessage.value = "局が見つかりませんでした: $stationName"
                    return@launch
                }

                // 通知時刻 = 放送開始時刻。放送中ならライブ再生、終了済みならタイムフリー
                val now = Instant.now()
                val start = Instant.ofEpochMilli(startEpochMillis)
                val end = Instant.ofEpochMilli(endEpochMillis)
                // endEpochMillis が不正 (0 など) の場合は「放送中」とみなしてライブ再生する
                val isBroadcasting = endEpochMillis <= 0 || now.isBefore(end)
                if (isBroadcasting) {
                    // 放送中 (開始前後の誤差含む) → ライブ再生
                    playLive(station)
                } else {
                    // 放送終了済み → タイムフリー再生
                    playTimefree(
                        station,
                        Program(
                            stationId = station.id,
                            ft = start,
                            to = end,
                            title = programTitle,
                            description = null,
                            performer = null,
                            episodeId = null,
                            imgUrl = null,
                        ),
                    )
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "再生を開始できませんでした"
            }
        }
    }

    /** 番組表を取得する。 */
    suspend fun getPrograms(stationId: String, dayOffset: Int = 0): List<Program> {
        val dayStart = RadikoTimeUtil.todayDayStart().plusSeconds(dayOffset * 24 * 3600L)
        val apiDate = RadikoTimeUtil.apiDateFor(dayStart)
        return programRepo.getPrograms(stationId, apiDate)
    }

    // --- 番組開始通知 (リマインダー) ---

    /** 指定の番組に通知が設定済みか。 */
    suspend fun isReminderSet(stationId: String, startEpochMillis: Long): Boolean =
        reminderRepo.isSet(stationId, startEpochMillis)

    /** 番組開始通知を設定する。開始時刻が過去なら何もしない。 */
    fun setReminder(station: Station, program: Program) {
        viewModelScope.launch {
            if (program.ft.toEpochMilli() <= Instant.now().toEpochMilli()) {
                _errorMessage.value = "開始済みの番組には通知を設定できません"
                return@launch
            }
            val reminder = StoredReminder(
                id = ReminderRepository.reminderId(station.id, program.ft.toEpochMilli()),
                stationId = station.id,
                stationName = station.name,
                programTitle = program.title,
                startEpochMillis = program.ft.toEpochMilli(),
                endEpochMillis = program.to.toEpochMilli(),
            )
            reminderRepo.add(reminder)
            ReminderScheduler(app).schedule(reminder)
            _errorMessage.value = "「${program.title}」の開始通知を設定しました"
        }
    }

    /** 番組開始通知を解除する。 */
    fun cancelReminder(reminder: StoredReminder) {
        viewModelScope.launch {
            reminderRepo.remove(reminder.id)
            ReminderScheduler(app).cancel(reminder.id)
        }
    }

    /** 現在のリマインダー一覧を取得する。 */
    suspend fun currentReminders(): List<StoredReminder> = reminderRepo.currentReminders()

    /**
     * 複数局の番組表を並列取得する (EPG グリッド用)。
     * 今日分は永続キャッシュを優先し、キャッシュ済みならネットワークを回避する。
     * @return 局ID → 番組リスト (失敗した局は空リスト)
     */
    suspend fun getProgramsForStations(stations: List<Station>, dayOffset: Int): Map<String, List<Program>> =
        getProgramsForStationsWithCache(stations, dayOffset)

    // --- タイムフリー (キャッシュ + 検索) ---

    /**
     * タイムフリー番組を取得し、キャッシュに保存する。
     * 指定局の指定日の番組表を取得してキャッシュに蓄積する。
     * 取得した番組はタイムフリー期間内 (過去7日) のもののみキャッシュする。
     */
    suspend fun loadAndCacheTimefree(stationId: String, stationName: String, dayOffset: Int): List<Program> {
        val dayStart = RadikoTimeUtil.todayDayStart().plusSeconds(dayOffset * 24 * 3600L)
        val apiDate = RadikoTimeUtil.apiDateFor(dayStart)

        // 今日分かつ永続キャッシュ済みなら、キャッシュから取得 (ネットワーク回避)
        val today = RadikoTimeUtil.apiDateFor(RadikoTimeUtil.todayDayStart())
        val cachedFromDisk: List<Program>? = if (apiDate == today) {
            runCatching {
                val areaId = _selectedAreaId.value
                val byStation = programCache.getPrograms(areaId, apiDate)
                // キーが存在すれば「取得済み」 (空リスト = 放送休止 も含む)。キーが無ければ未取得。
                if (byStation.containsKey(stationId)) {
                    byStation[stationId].orEmpty()
                } else {
                    null
                }
            }.getOrNull()
        } else {
            null
        }
        val programs = cachedFromDisk ?: programRepo.getPrograms(stationId, apiDate)

        // 現在のキャッシュにマージして保存
        val current = timefreeCache.currentCachedPrograms()[stationId].orEmpty()
        val cachedWithNames = programs.map { p ->
            timefreeCache.toCached(p).copy(stationName = stationName)
        }
        // 重複除去 (ft で判別) + 期間内のみ保持
        val merged = (current + cachedWithNames)
            .distinctBy { it.ftEpochMillis }
            .filter { timefreeCache.isWithinTimefree(Instant.ofEpochMilli(it.ftEpochMillis)) }
        timefreeCache.putStationPrograms(stationId, merged)
        return programs
    }

    /**
     * タイムフリー番組を検索する。
     * 永続キャッシュ (TimefreeCacheRepository) + 今日の番組表キャッシュ (ProgramCacheRepository) を
     * マージして全局横断検索する。開いた局しか出ない問題を解消する。
     *
     * 検索結果は「現在のエリアの局」のみに限定する (エリア変更後も旧エリアの番組が混ざらない)。
     * @param query 検索キーワード (空なら全件・新しい順)
     * @param stations 現在のエリアの局一覧 (フィルタと局名補完用)
     */
    suspend fun searchTimefree(query: String, stations: List<Station>): List<CachedTimefreeProgram> {
        if (stations.isEmpty()) return emptyList()
        val byId = stations.associateBy { it.id }
        val stationIds = byId.keys
        // 1) タイムフリーキャッシュ (局選択で取得済みの分) — 現在エリアの局のみ
        val timefree = timefreeCache.currentCachedPrograms().values.flatten()
            .filter { it.stationId in stationIds }
        // 2) 今日の番組表キャッシュ (起動時プリロード分) をタイムフリー期間内のものに変換
        val todayCache = runCatching {
            val areaId = _selectedAreaId.value
            val apiDate = RadikoTimeUtil.apiDateFor(RadikoTimeUtil.todayDayStart())
            programCache.getPrograms(areaId, apiDate).values.flatten()
                .filter { it.stationId in stationIds }
                .map { timefreeCache.toCached(it) }
        }.getOrDefault(emptyList())
        // マージ (ft で重複除去) + 期間内フィルタ + 局名補完
        val enriched = (timefree + todayCache)
            .distinctBy { it.stationId + "|" + it.ftEpochMillis }
            .filter { timefreeCache.isWithinTimefree(Instant.ofEpochMilli(it.ftEpochMillis)) }
            .map { timefreeCache.withStationName(it, byId[it.stationId]?.name ?: "") }
        if (query.isBlank()) {
            return enriched.sortedByDescending { it.ftEpochMillis }
        }
        val lower = query.trim().lowercase()
        return enriched.filter {
            it.title.lowercase().contains(lower) ||
                it.performer?.lowercase()?.contains(lower) == true ||
                it.stationName.lowercase().contains(lower)
        }.sortedByDescending { it.ftEpochMillis }
    }

    /**
     * タイムフリーのキャッシュ済み一覧を取得する (検索なし・局名補完済み)。
     * タイムフリーキャッシュ + 今日の番組表キャッシュをマージする。
     * 現在のエリアの局のみに限定する。
     */
    suspend fun cachedTimefreePrograms(stations: List<Station>): List<CachedTimefreeProgram> {
        if (stations.isEmpty()) return emptyList()
        val byId = stations.associateBy { it.id }
        val stationIds = byId.keys
        val timefree = timefreeCache.currentCachedPrograms().values.flatten()
            .filter { it.stationId in stationIds }
        val todayCache = runCatching {
            val areaId = _selectedAreaId.value
            val apiDate = RadikoTimeUtil.apiDateFor(RadikoTimeUtil.todayDayStart())
            programCache.getPrograms(areaId, apiDate).values.flatten()
                .filter { it.stationId in stationIds }
                .map { timefreeCache.toCached(it) }
        }.getOrDefault(emptyList())
        return (timefree + todayCache)
            .distinctBy { it.stationId + "|" + it.ftEpochMillis }
            .filter { timefreeCache.isWithinTimefree(Instant.ofEpochMilli(it.ftEpochMillis)) }
            .map { timefreeCache.withStationName(it, byId[it.stationId]?.name ?: "") }
            .sortedByDescending { it.ftEpochMillis }
    }

    /** キャッシュ済みタイムフリー番組を再生する。 */
    fun playCachedTimefree(station: Station, cached: CachedTimefreeProgram) {
        viewModelScope.launch {
            _errorMessage.value = null
            try {
                applyBackgroundPlayback(_settings.value.backgroundPlayback)

                val session = auth.getSession(_selectedAreaId.value)
                radikoPlayer.setAuth(session.token, session.areaId)
                // シーク時のプレイリスト再構築用に現在のタイムフリー再生コンテキストを保持する
                timefreeContext = TimefreeSeekContext(
                    station = station,
                    ft = Instant.ofEpochMilli(cached.ftEpochMillis),
                    to = Instant.ofEpochMilli(cached.toEpochMillis),
                    token = session.token,
                )

                val resolver = StreamUrlResolver(app.apiClient)
                val medialistUrl = resolver.resolveTimefreeMedialistUrl(
                    stationId = station.id,
                    token = session.token,
                    from = Instant.ofEpochMilli(cached.ftEpochMillis),
                    to = Instant.ofEpochMilli(cached.toEpochMillis),
                )
                val durationMs = (cached.toEpochMillis - cached.ftEpochMillis).coerceAtLeast(0L)
                radikoPlayer.playMedialist(medialistUrl, durationOverrideMs = durationMs)

                _nowPlaying.value = NowPlaying(
                    stationId = station.id,
                    stationName = station.name,
                    title = cached.title,
                    isTimefree = true,
                    stationLogoUrl = station.logoUrl,
                    programImgUrl = cached.imgUrl,
                    description = cached.description,
                    performer = cached.performer,
                )
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "タイムフリー再生を開始できませんでした"
            }
        }
    }

    /** 再生エラーを UI に反映する。 */
    fun consumePlayerError(): String? {
        val err = playerUiState.value.error
        _errorMessage.value = err?.message
        // プレイヤーのエラーをクリアする (次回再生時に古いエラーを再表示しない)
        radikoPlayer.clearError()
        return err?.message
    }

    /** エラーメッセージを表示する。 */
    fun showError(message: String) {
        _errorMessage.value = message
    }

    /** 一時停止 */
    fun pause() = radikoPlayer.pause()

    /** 再生再開 */
    fun play() = radikoPlayer.play()

    /**
     * シーク。
     *
     * タイムフリー再生中は ExoPlayer.seekTo を使わず、`seek` パラメータを
     * 「番組先頭 + 位置」に設定したプレイリストを再リクエストして再ロードする。
     * radiko のタイムフリーは l=300 (約5分) のスライディングウィンドウ配信のため、
     * ExoPlayer.seekTo はロード済みウィンドウ内しか移動できず、先へシークすると
     * 永久 BUFFERING になる (実機検証済み)。
     */
    fun seekTo(positionMs: Long) {
        val ctx = timefreeContext ?: run {
            // タイムフリー以外は ExoPlayer 直接シーク (ほぼ使われない)
            radikoPlayer.seekTo(positionMs)
            return
        }
        viewModelScope.launch {
            _errorMessage.value = null
            try {
                val resolver = StreamUrlResolver(app.apiClient)
                val url = resolver.resolveTimefreeMedialistUrl(
                    stationId = ctx.station.id,
                    token = ctx.token,
                    from = ctx.ft,
                    to = ctx.to,
                    seekOffsetMs = positionMs,
                )
                val durationMs = (ctx.to.toEpochMilli() - ctx.ft.toEpochMilli()).coerceAtLeast(0L)
                radikoPlayer.playMedialist(url, durationOverrideMs = durationMs, startPositionMs = positionMs)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "シークに失敗しました"
            }
        }
    }

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

    /** 聞いた番組の履歴を全削除する。 */
    fun clearHistory() {
        viewModelScope.launch { historyRepo.clear() }
    }

    /** 履歴エントリから再生する。タイムフリー期間内ならタイムフリー再生を試みる。 */
    fun playHistoryEntry(entry: HistoryEntry) {
        viewModelScope.launch {
            val all = stationRepo.getStations()
            val station = all.firstOrNull { it.id == entry.stationId }
            if (station == null) {
                _errorMessage.value = "局が見つかりません: ${entry.stationName}"
                return@launch
            }
            // タイムフリー期間内 (7日) ならタイムフリー再生、それ以外はライブを試す
            val now = Instant.now().toEpochMilli()
            if (now - entry.listenedAtEpochMillis <= TimefreeCacheRepository.MAX_AGE_MILLIS) {
                // 履歴の番組 (放送時刻不明) は現在時刻基準でライブ再生を優先し、
                // 失敗時はタイムフリー一覧から該当番組を探して再生する
                playLive(station)
            } else {
                _errorMessage.value = "この番組はタイムフリー期間を過ぎています"
            }
        }
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
