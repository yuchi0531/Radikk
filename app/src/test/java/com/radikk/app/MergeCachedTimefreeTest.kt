package com.radikk.app

import com.radikk.app.data.timefree.CachedTimefreeProgram
import com.radikk.app.ui.mergeCachedTimefree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * タイムフリーキャッシュのマージロジック (mergeCachedTimefree) のテスト。
 *
 * preloadTimefreeCache の「8日並列 × 全局 read-modify-write」による lost update
 * (各局が最後に書き込んだ 1 日分しか残らない) を防ぐため、マージは純関数に抽出されている。
 * このテストは「複数日分を累積しても全て保持される」ことを回帰ガードする。
 */
class MergeCachedTimefreeTest {

    private fun program(
        ftEpochMillis: Long,
        title: String = "番組",
        stationId: String = "TBS",
    ): CachedTimefreeProgram = CachedTimefreeProgram(
        stationId = stationId,
        stationName = "TBSラジオ",
        ftEpochMillis = ftEpochMillis,
        toEpochMillis = ftEpochMillis + 3600_000L,
        title = title,
        description = null,
        performer = null,
        imgUrl = null,
    )

    private val day0Ft = 1_752_600_000_000L  // 今日分
    private val day1Ft = 1_752_510_000_000L  // 1日前
    private val day2Ft = 1_752_420_000_000L  // 2日前

    @Test
    fun `新規日付を既存キャッシュに追記する`() {
        val current = listOf(program(day0Ft, title = "今日の番組"))
        val incoming = listOf(program(day1Ft, title = "昨日の番組"))

        val merged = mergeCachedTimefree(current, incoming) { true }

        assertEquals(2, merged.size)
        assertTrue(merged.any { it.title == "今日の番組" })
        assertTrue(merged.any { it.title == "昨日の番組" })
    }

    @Test
    fun `同じ ft は重複せず1件にまとまる`() {
        val current = listOf(program(day0Ft, title = "元のタイトル"))
        val incoming = listOf(program(day0Ft, title = "新しいタイトル"))

        val merged = mergeCachedTimefree(current, incoming) { true }

        assertEquals(1, merged.size)
        // distinctBy は最初の要素を残す (current 優先)
        assertEquals("元のタイトル", merged.single().title)
    }

    @Test
    fun `タイムフリー期間外の番組は除外される`() {
        val now = Instant.parse("2026-08-07T12:00:00Z")
        val inWindow = now.minusSeconds(3600)      // 1時間前
        val outOfWindow = now.minusSeconds(8 * 24 * 3600) // 8日前 → 期間外
        val future = now.plusSeconds(3600)          // 未来 → 期間外

        val current = listOf(program(inWindow.toEpochMilli()))
        val incoming = listOf(
            program(outOfWindow.toEpochMilli()),
            program(future.toEpochMilli()),
        )

        // 実際の isWithinTimefree と同じく「下限 (now-7日) 以上かつ now 以下」の両端で判定する
        val cutoff = now.minusSeconds(7 * 24 * 3600)
        val merged = mergeCachedTimefree(current, incoming) { it >= cutoff && it <= now }

        assertEquals(1, merged.size)
        assertEquals(inWindow.toEpochMilli(), merged.single().ftEpochMillis)
    }

    @Test
    fun `複数日分を累積しても全日付が保持される (lost update 回帰ガード)`() {
        // 旧実装の競合を再現: 8日分を「別々のタスク」が書いていたため最後の 1 日分しか残らなかった。
        // 新実装は全 8 日分をメモリ上で累積してから 1 回だけ書く。この累積を順次シミュレートする。
        var accumulated = emptyList<CachedTimefreeProgram>()
        val days = 0..7 // 8日分 (0=今日 ... 7=7日前)
        val ftByOffset = days.associateWith { 1_752_600_000_000L - it * 24 * 3600_000L }

        for (offset in days) {
            val incoming = listOf(program(ftByOffset.getValue(offset), title = "D$offset"))
            accumulated = mergeCachedTimefree(accumulated, incoming) { true }
        }

        assertEquals("8日分すべて保持される", 8, accumulated.size)
        val keptFts = accumulated.map { it.ftEpochMillis }.toSet()
        days.forEach { offset ->
            assertTrue("offset $offset の番組が残っている", keptFts.contains(ftByOffset.getValue(offset)))
        }
    }

    @Test
    fun `既存キャッシュと新規分で重複があっても全日付は保持される`() {
        // 既存キャッシュに今日分が入った状態から8日分を追記 (loadAndCacheTimefree との併用を想定)
        var accumulated = listOf(program(day0Ft, title = "既存の今日分"))
        val days = 0..7
        val ftByOffset = days.associateWith { 1_752_600_000_000L - it * 24 * 3600_000L }

        for (offset in days) {
            val incoming = listOf(program(ftByOffset.getValue(offset), title = "D$offset"))
            accumulated = mergeCachedTimefree(accumulated, incoming) { true }
        }

        assertEquals("8日分 (今日は既存+新規が同一 ft で1件にまとまる)", 8, accumulated.size)
        days.forEach { offset ->
            assertTrue(accumulated.any { it.ftEpochMillis == ftByOffset.getValue(offset) })
        }
    }
}
