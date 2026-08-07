package com.radikk.app

import com.radikk.app.data.timefree.TimefreeCacheRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * タイムフリー期間外破棄ロジックのテスト。
 * radiko のタイムフリーは「現在時刻から過去7日間 (168時間)」のみ再生可能。
 */
class TimefreeCacheTest {

    private val now: Instant = Instant.parse("2026-08-07T12:00:00Z")
    private val nowMillis = now.toEpochMilli()

    @Test
    fun `現在の番組はタイムフリー期間内`() {
        assertTrue(TimefreeCacheRepository.isWithinTimefree(nowMillis - 60_000, nowMillis))
    }

    @Test
    fun `7日以内の過去番組は期間内`() {
        // 6日前
        assertTrue(TimefreeCacheRepository.isWithinTimefree(nowMillis - 6 * 24 * 3600_000L, nowMillis))
        // ほぼ7日前 (境界ぎりぎり)
        assertTrue(TimefreeCacheRepository.isWithinTimefree(nowMillis - (7 * 24 * 3600_000L - 60_000), nowMillis))
    }

    @Test
    fun `7日ちょうど前は期間内 (境界)`() {
        // cutoff は now - 7日。7日ちょうど前の番組はギリギリ期間内
        assertTrue(TimefreeCacheRepository.isWithinTimefree(nowMillis - 7 * 24 * 3600_000L, nowMillis))
    }

    @Test
    fun `7日より前の番組は期間外 (破棄対象)`() {
        // 7日+1秒前は期間外
        assertFalse(TimefreeCacheRepository.isWithinTimefree(nowMillis - (7 * 24 * 3600_000L + 1000), nowMillis))
        // 8日前
        assertFalse(TimefreeCacheRepository.isWithinTimefree(nowMillis - 8 * 24 * 3600_000L, nowMillis))
        // 10日前
        assertFalse(TimefreeCacheRepository.isWithinTimefree(nowMillis - 10 * 24 * 3600_000L, nowMillis))
    }

    @Test
    fun `未来の番組は期間外`() {
        assertFalse(TimefreeCacheRepository.isWithinTimefree(nowMillis + 60_000, nowMillis))
        assertFalse(TimefreeCacheRepository.isWithinTimefree(nowMillis + 3600_000L, nowMillis))
    }
}
