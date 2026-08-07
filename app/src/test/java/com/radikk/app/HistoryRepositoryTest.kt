package com.radikk.app

import android.content.Context
import android.content.ContextWrapper
import com.radikk.app.data.history.HistoryEntry
import com.radikk.app.data.history.HistoryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * HistoryRepository の単体テスト。
 *
 * プロジェクトの local unit test には Robolectric も androidx.test:core
 * (ApplicationProvider) も依存に無いため、Android framework の
 * mockable jar に含まれる具象クラス ContextWrapper を継承した
 * フェイク Context で Context 依存を解決する (getFilesDir のみ利用)。
 * DataStore のストレージ層は java.io.File + okio のみで動くため、
 * このフェイク Context で実ファイルベースの DataStore が JVM 上で動作する。
 */
class HistoryRepositoryTest {

    /** テストごとに独立した一時ディレクトリを返すフェイク Context。 */
    private class FakeContext(filesDir: File) : ContextWrapper(null) {
        private val files = filesDir
        override fun getFilesDir(): File = files
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.radikk.app.test"
    }

    private lateinit var tempDir: File
    private lateinit var repo: HistoryRepository

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("radikk-history-test").toFile()
        repo = HistoryRepository(FakeContext(tempDir))
        // DataStore の preferencesDataStore delegate は JVM 全体でシングルトン
        // (名前 "radikk_history" で共有) なので、テスト間の状態を確実に分離する。
        runTest { repo.clear() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun entry(
        stationId: String,
        title: String,
        atEpochMillis: Long,
        stationName: String = "テスト局",
        isTimefree: Boolean = false,
    ) = HistoryEntry(
        stationId = stationId,
        stationName = stationName,
        programTitle = title,
        isTimefree = isTimefree,
        listenedAtEpochMillis = atEpochMillis,
    )

    @Test
    fun `add は新しい順で返す`() = runTest {
        repo.add(entry("TBS", "朝のニュース", atEpochMillis = 1000))
        repo.add(entry("QR", "昼の番組", atEpochMillis = 2000))
        repo.add(entry("LFR", "夜の番組", atEpochMillis = 3000))

        val history = repo.history.first()
        assertEquals(3, history.size)
        assertEquals(listOf("LFR", "QR", "TBS"), history.map { it.stationId })
        assertEquals(listOf(3000L, 2000L, 1000L), history.map { it.listenedAtEpochMillis })
    }

    @Test
    fun `同一番組の重複は更新される`() = runTest {
        repo.add(entry("TBS", "朝のニュース", atEpochMillis = 1000))
        // 同じ局 + 番組タイトル + 種別 (ライブ) を後から追加
        repo.add(entry("TBS", "朝のニュース", atEpochMillis = 5000))

        val history = repo.history.first()
        assertEquals(1, history.size)
        assertEquals("TBS", history.single().stationId)
        assertEquals("朝のニュース", history.single().programTitle)
        // 時刻は後から追加した方の値に更新される
        assertEquals(5000L, history.single().listenedAtEpochMillis)
    }

    @Test
    fun `50件を超えると古いものが削除される`() = runTest {
        repeat(55) { i ->
            repo.add(entry("station-$i", "番組$i", atEpochMillis = i.toLong() + 1))
        }

        val history = repo.history.first()
        assertEquals(HistoryRepository.MAX_ENTRIES, history.size)
        assertEquals(50, history.size)
        // 最新の 50 件 (station-5 〜 station-54) が残り、古い 5 件 (station-0 〜 station-4) は捨てられる
        assertEquals("station-54", history.first().stationId)
        assertEquals("station-5", history.last().stationId)
        assertTrue(history.none { it.stationId == "station-0" })
        assertTrue(history.none { it.stationId == "station-4" })
    }

    @Test
    fun `clear で空になる`() = runTest {
        repo.add(entry("TBS", "朝のニュース", atEpochMillis = 1000))
        repo.add(entry("QR", "昼の番組", atEpochMillis = 2000))

        assertTrue(repo.history.first().isNotEmpty())

        repo.clear()

        assertTrue(repo.history.first().isEmpty())
    }
}
