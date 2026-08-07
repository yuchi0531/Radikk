package com.radikk.app

import android.app.Application
import com.radikk.app.data.api.FullKeyProvider
import com.radikk.app.data.api.RadikoApiClient
import com.radikk.app.data.auth.AuthRepository
import com.radikk.app.data.datastore.SettingsRepository
import com.radikk.app.data.favorite.FavoriteRepository
import com.radikk.app.data.programcache.ProgramCacheRepository
import com.radikk.app.data.reminder.ReminderRepository
import com.radikk.app.data.repository.ProgramRepository
import com.radikk.app.data.repository.StationRepository
import com.radikk.app.data.timefree.TimefreeCacheRepository

/**
 * Radikk アプリケーション。
 * DI のワイヤリングを担当する。シンプルな手動 DI (コンテナなし)。
 */
class RadikkApplication : Application() {

    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var apiClient: RadikoApiClient
        private set
    lateinit var authRepository: AuthRepository
        private set
    lateinit var stationRepository: StationRepository
        private set
    lateinit var programRepository: ProgramRepository
        private set
    lateinit var reminderRepository: ReminderRepository
        private set
    lateinit var timefreeCacheRepository: TimefreeCacheRepository
        private set
    lateinit var programCacheRepository: ProgramCacheRepository
        private set
    lateinit var favoriteRepository: FavoriteRepository
        private set

    override fun onCreate() {
        super.onCreate()

        settingsRepository = SettingsRepository(this)
        apiClient = RadikoApiClient()
        val fullKeyProvider = FullKeyProvider(this)

        authRepository = AuthRepository(settingsRepository, apiClient, fullKeyProvider)

        // 認証トークンは AuthRepository 経由で遅延取得する (現在のエリア)
        stationRepository = StationRepository(apiClient)
        programRepository = ProgramRepository(apiClient) {
            val areaId = settingsRepository.currentSettings().areaId
            runCatching { authRepository.getSession(areaId).token }.getOrNull()
        }
        reminderRepository = ReminderRepository(this)
        timefreeCacheRepository = TimefreeCacheRepository(this)
        programCacheRepository = ProgramCacheRepository(this)
        favoriteRepository = FavoriteRepository(this)
    }
}
