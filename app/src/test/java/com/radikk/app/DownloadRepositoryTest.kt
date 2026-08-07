package com.radikk.app

import android.content.Context
import android.content.ContextWrapper
import com.radikk.app.data.download.DownloadRepository
import com.radikk.app.data.download.DownloadedProgram
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
 * DownloadRepository の単体テスト。
 *
 * プロジェクトの local unit test には Robolectric も androidx.test:core
 * (ApplicationProvider) も依存に無いため、Android framework の
 * mockable jar に含まれる具象クラス ContextWrapper を継承した
 * フェイク Context で Context 依存を解決する (getFilesDir のみ利用)。
 * DataStore のストレージ層は java.io.File + okio のみで動くため、
 * このフェイク Context で実ファイルベースの DataStore が JVM 上で動作する。
 */
class DownloadRepositoryTest {

    /** テストごとに独立した一時ディレクトリを返すフェイク Context。 */
    private class FakeContext(filesDir: File) : ContextWrapper(null) {
        private val files = filesDir
        override fun getFilesDir(): File = files
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.radikk.app.test"
    }

    private lateinit var tempDir: File
    private lateinit var repo: DownloadRepository

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("radikk-download-test").toFile()
        repo = DownloadRepository(FakeContext(tempDir))
        // DataStore の preferencesDataStore delegate は JVM 全体でシングルトン
        // (名前 "radikk_downloads" で共有) なので、テスト間の状態を確実に分離する。
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
        downloadedAtEpochMillis: Long = ftEpochMillis,
    ) = DownloadedProgram(
        stationId = stationId,
        stationName = stationName,
        programTitle = title,
        ftEpochMillis = ftEpochMillis,
        toEpochMillis = ftEpochMillis + 60 * 60 * 1000L,
        filePath = "/tmp/$stationId-$ftEpochMillis.aac",
        downloadedAtEpochMillis = downloadedAtEpochMillis,
    )

    @Test
    fun `add は新しい順 (ダウンロード日時降順) で返す`() = runTest {
        repo.add(entry("TBS", "朝のニュース", ftEpochMillis = 1000, downloadedAtEpochMillis = 1000))
        repo.add(entry("QR", "昼の番組", ftEpochMillis = 2000, downloadedAtEpochMillis = 2000))
        repo.add(entry("LFR", "夜の番組", ftEpochMillis = 3000, downloadedAtEpochMillis = 3000))

        val downloads = repo.downloads.first()
        assertEquals(3, downloads.size)
        assertEquals(listOf("LFR", "QR", "TBS"), downloads.map { it.stationId })
    }

    @Test
    fun `同一番組 (局+ft) は重複登録されず置き換わる`() = runTest {
        repo.add(entry("TBS", "朝のニュース", ftEpochMillis = 1000))
        // 同じ局 + ft を後から追加 (タイトルは変更されている想定)
        repo.add(entry("TBS", "朝のニュース 改訂", ftEpochMillis = 1000, downloadedAtEpochMillis = 5000))

        val downloads = repo.downloads.first()
        assertEquals(1, downloads.size)
        assertEquals("TBS", downloads.single().stationId)
        assertEquals("朝のニュース 改訂", downloads.single().programTitle)
    }

    @Test
    fun `100件を超えると古いものが削除される`() = runTest {
        repeat(105) { i ->
            repo.add(entry("station-$i", "番組$i", ftEpochMillis = i.toLong() + 1))
        }

        val downloads = repo.downloads.first()
        assertEquals(DownloadRepository.MAX_ENTRIES, downloads.size)
        assertEquals(100, downloads.size)
        // 最新の 100 件 (station-5 〜 station-104) が残り、古い 5 件 (station-0 〜 station-4) は捨てられる
        assertEquals("station-104", downloads.first().stationId)
        assertEquals("station-5", downloads.last().stationId)
        assertTrue(downloads.none { it.stationId == "station-0" })
        assertTrue(downloads.none { it.stationId == "station-4" })
    }

    @Test
    fun `上限超過で捨てられたエントリが onEvicted に通知される`() = runTest {
        val evicted = mutableListOf<DownloadedProgram>()
        repo.onEvicted = { evicted.addAll(it) }

        repeat(103) { i ->
            repo.add(entry("station-$i", "番組$i", ftEpochMillis = i.toLong() + 1))
        }

        // 103 件登録 → 100 件に丸められ、古い 3 件 (station-0 〜 station-2) が通知される
        assertEquals(3, evicted.size)
        assertEquals(listOf("station-0", "station-1", "station-2"), evicted.map { it.stationId })
    }

    @Test
    fun `remove は一致する番組のみ削除する`() = runTest {
        repo.add(entry("TBS", "朝のニュース", ftEpochMillis = 1000))
        repo.add(entry("QR", "昼の番組", ftEpochMillis = 2000))

        repo.remove("TBS", 1000)

        val downloads = repo.downloads.first()
        assertEquals(1, downloads.size)
        assertEquals("QR", downloads.single().stationId)
    }

    @Test
    fun `clear で空になる`() = runTest {
        repo.add(entry("TBS", "朝のニュース", ftEpochMillis = 1000))
        repo.add(entry("QR", "昼の番組", ftEpochMillis = 2000))

        assertTrue(repo.downloads.first().isNotEmpty())

        repo.clear()

        assertTrue(repo.downloads.first().isEmpty())
    }
}
