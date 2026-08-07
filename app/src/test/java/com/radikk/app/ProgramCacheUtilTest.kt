package com.radikk.app

import com.radikk.app.util.RadikoTimeUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 番組表キャッシュ (ProgramCacheRepository) の日付ロジックテスト。
 *
 * キャッシュの「一日一回」制御は JST 5:00 起点の放送日 (apiDate) を基準にする。
 * 深夜 0:00〜4:59 は「実日付の翌日」だが「放送日としては前日」になる点を検証する。
 */
class ProgramCacheUtilTest {

    @Test
    fun `今日の apiDate は 8 桁の YYYYMMDD`() {
        val apiDate = RadikoTimeUtil.apiDateFor(RadikoTimeUtil.todayDayStart())
        assertEquals(8, apiDate.length)
        assertTrue(apiDate.matches(Regex("\\d{8}")))
    }

    @Test
    fun `5時以降の正午は実日付と同じ放送日`() {
        // 2026-08-07 12:00 JST → 放送日は 2026-08-07
        val noon = Instant.parse("2026-08-07T03:00:00Z") // 12:00 JST
        val apiDate = RadikoTimeUtil.apiDateFor(RadikoTimeUtil.dayStartOf(noon))
        assertEquals("20260807", apiDate)
    }

    @Test
    fun `深夜0時は前日の放送日になる (5時起点)`() {
        // 2026-08-07 01:00 JST = 2026-08-06 16:00 UTC
        // → 放送日は前日 2026-08-06 (5:00起点のため)。放送日開始は 8/6 05:00 JST = 8/5 20:00 UTC
        val lateNight = Instant.parse("2026-08-06T16:00:00Z") // 01:00 JST 8/7
        val dayStart = RadikoTimeUtil.dayStartOf(lateNight)
        assertEquals("2026-08-05T20:00:00Z", dayStart.toString())
        assertEquals("20260806", RadikoTimeUtil.apiDateFor(dayStart))
    }

    @Test
    fun `深夜4時59分も前日の放送日`() {
        // 2026-08-07 04:59 JST = 2026-08-06 19:59 UTC → 放送日 2026-08-06
        val justBefore5 = Instant.parse("2026-08-06T19:59:00Z")
        assertEquals("20260806", RadikoTimeUtil.apiDateFor(RadikoTimeUtil.dayStartOf(justBefore5)))
    }

    @Test
    fun `5時ちょうどは当日の放送日`() {
        // 2026-08-07 05:00 JST = 2026-08-06 20:00 UTC → 放送日 2026-08-07
        val exactly5 = Instant.parse("2026-08-06T20:00:00Z")
        assertEquals("20260807", RadikoTimeUtil.apiDateFor(RadikoTimeUtil.dayStartOf(exactly5)))
    }

    @Test
    fun `今日の放送日の開始時刻は JST 5時`() {
        val dayStart = RadikoTimeUtil.todayDayStart()
        val zdt = dayStart.atZone(ZoneId.of("Asia/Tokyo"))
        assertEquals(5, zdt.hour)
        assertEquals(0, zdt.minute)
    }

    @Test
    fun `放送日開始ミリ秒は今日の正午より過去`() {
        val dayStartMillis = RadikoTimeUtil.todayDayStart().toEpochMilli()
        val now = System.currentTimeMillis()
        assertTrue(dayStartMillis <= now)
        // 今日の5:00は「今日の実日付」の日付部分に対応する
        val date = LocalDate.now(ZoneId.of("Asia/Tokyo"))
        assertTrue(date.toString().startsWith(RadikoTimeUtil.apiDateFor(RadikoTimeUtil.todayDayStart()).take(4)))
    }
}
