package com.radikk.app

import android.content.Context
import android.content.ContextWrapper
import com.radikk.app.data.favorite.FavoriteEntry
import com.radikk.app.data.favorite.FavoriteRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * FavoriteRepository の単体テスト。
 *
 * プロジェクトの local unit test には Robolectric も androidx.test:core
 * (ApplicationProvider) も依存に無いため、Android framework の
 * mockable jar に含まれる具象クラス ContextWrapper を継承した
 * フェイク Context で Context 依存を解決する (getFilesDir のみ利用)。
 * DataStore のストレージ層は java.io.File + okio のみで動くため、
 * このフェイク Context で実ファイルベースの DataStore が JVM 上で動作する。
 */
class FavoriteRepositoryTest {

    /** テストごとに独立した一時ディレクトリを返すフェイク Context。 */
    private class FakeContext(filesDir: File) : ContextWrapper(null) {
        private val files = filesDir
        override fun getFilesDir(): File = files
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.radikk.app.test"
    }

    private lateinit var tempDir: File
    private lateinit var repo: FavoriteRepository

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("radikk-favorite-test").toFile()
        repo = FavoriteRepository(FakeContext(tempDir))
        // DataStore の preferencesDataStore delegate は JVM 全体でシングルトン
        // (名前 "radikk_favorites" で共有) なので、テスト間の状態を確実に分離する。
        runTest { repo.clear() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun entry(
        stationId: String,
        title: String,
        ftEpochMillis: Long,
        stationName: String = "テスト局",
        addedAtEpochMillis: Long = ftEpochMillis,
    ) = FavoriteEntry(
        stationId = stationId,
        stationName = stationName,
        programTitle = title,
        ftEpochMillis = ftEpochMillis,
        toEpochMillis = ftEpochMillis + 60 * 60 * 1000L,
        addedAtEpochMillis = addedAtEpochMillis,
    )

    @Test
    fun `add は新しい順 (addedAt 降順) で返す`() = runTest {
        repo.add(entry("TBS", "朝のニュース", ftEpochMillis = 1000, addedAtEpochMillis = 1000))
        repo.add(entry("QR", "昼の番組", ftEpochMillis = 2000, addedAtEpochMillis = 2000))
        repo.add(entry("LFR", "夜の番組", ftEpochMillis = 3000, addedAtEpochMillis = 3000))

        val favorites = repo.favorites.first()
        assertEquals(3, favorites.size)
        assertEquals(listOf("LFR", "QR", "TBS"), favorites.map { it.stationId })
    }

    @Test
    fun `同一番組 (局+ft) は重複登録されず置き換わる`() = runTest {
        repo.add(entry("TBS", "朝のニュース", ftEpochMillis = 1000))
        // 同じ局 + ft を後から追加 (タイトルは変更されている想定)
        repo.add(entry("TBS", "朝のニュース 改訂", ftEpochMillis = 1000, addedAtEpochMillis = 5000))

        val favorites = repo.favorites.first()
        assertEquals(1, favorites.size)
        assertEquals("TBS", favorites.single().stationId)
        assertEquals("朝のニュース 改訂", favorites.single().programTitle)
    }

    @Test
    fun `100件を超えると古いものが削除される`() = runTest {
        repeat(105) { i ->
            repo.add(entry("station-$i", "番組$i", ftEpochMillis = i.toLong() + 1))
        }

        val favorites = repo.favorites.first()
        assertEquals(FavoriteRepository.MAX_ENTRIES, favorites.size)
        assertEquals(100, favorites.size)
        // 最新の 100 件 (station-5 〜 station-104) が残り、古い 5 件 (station-0 〜 station-4) は捨てられる
        assertEquals("station-104", favorites.first().stationId)
        assertEquals("station-5", favorites.last().stationId)
        assertTrue(favorites.none { it.stationId == "station-0" })
        assertTrue(favorites.none { it.stationId == "station-4" })
    }

    @Test
    fun `remove は一致する番組のみ削除する`() = runTest {
        repo.add(entry("TBS", "朝のニュース", ftEpochMillis = 1000))
        repo.add(entry("QR", "昼の番組", ftEpochMillis = 2000))

        repo.remove("TBS", 1000)

        val favorites = repo.favorites.first()
        assertEquals(1, favorites.size)
        assertEquals("QR", favorites.single().stationId)
    }

    @Test
    fun `isFavorite は登録状態を返す`() = runTest {
        repo.add(entry("TBS", "朝のニュース", ftEpochMillis = 1000))

        assertTrue(repo.isFavorite("TBS", 1000))
        assertFalse(repo.isFavorite("TBS", 999))
        assertFalse(repo.isFavorite("QR", 1000))
    }

    @Test
    fun `clear で空になる`() = runTest {
        repo.add(entry("TBS", "朝のニュース", ftEpochMillis = 1000))
        repo.add(entry("QR", "昼の番組", ftEpochMillis = 2000))

        assertTrue(repo.favorites.first().isNotEmpty())

        repo.clear()

        assertTrue(repo.favorites.first().isEmpty())
    }
}
