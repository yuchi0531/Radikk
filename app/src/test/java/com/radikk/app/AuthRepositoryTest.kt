package com.radikk.app

import com.radikk.app.data.api.RadikoApi
import com.radikk.app.data.auth.AuthRepository
import com.radikk.app.util.RadikoTimeUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Base64

/**
 * 純粋ロジックの単体テスト。
 * buildPartialKey は Companion の純粋関数としてテストする。
 */
class AuthRepositoryTest {

    @Test
    fun `partialKey は fullKey デコードからスライスを再エンコードする`() {
        // 既知の fullKey 相当: "hello world!" の base64
        val fullKey = Base64.getEncoder().encodeToString("hello world!".toByteArray())
        val repo = AuthRepository.buildPartialKey(fullKey, 0, 5)
        assertEquals("aGVsbG8=", repo)

        // offset=6, length=5 → "world"
        val repo2 = AuthRepository.buildPartialKey(fullKey, 6, 5)
        assertEquals("d29ybGQ=", repo2)
    }

    @Test
    fun `partialKey はオフセット境界を検証する`() {
        val fullKey = Base64.getEncoder().encodeToString("abc".toByteArray())
        try {
            AuthRepository.buildPartialKey(fullKey, 0, 4) // 長さ超過
            throw AssertionError("IllegalArgumentException が発生すべき")
        } catch (e: IllegalArgumentException) {
            // OK
        }
    }

    @Test
    fun `user id は 32 hex 文字を生成する`() {
        val id = RadikoApi.userId("test-seed")
        assertEquals(32, id.length)
        assertTrue(id.matches(Regex("[0-9a-f]{32}")))
        // 同じシードなら同じ値
        assertEquals(id, RadikoApi.userId("test-seed"))
    }

    @Test
    fun `JST 14桁を Instant に変換できる`() {
        val jst14 = "20260807123000"
        val instant = RadikoTimeUtil.parseJst14ToInstant(jst14)
        // 2026-08-07 12:30:00 JST = 2026-08-07 03:30:00 UTC
        val utc = instant.atZone(ZoneId.of("UTC"))
        assertEquals(2026, utc.year)
        assertEquals(8, utc.monthValue)
        assertEquals(7, utc.dayOfMonth)
        assertEquals(3, utc.hour)
        assertEquals(30, utc.minute)
    }

    @Test
    fun `JST 5時起点の放送日判定`() {
        // 2026-08-07 03:00 JST → 前日 (8/6) の 5:00 が放送日開始
        val jst3am = ZonedDateTime.of(2026, 8, 7, 3, 0, 0, 0, RadikoTimeUtil.JST).toInstant()
        val dayStart = RadikoTimeUtil.dayStartOf(jst3am)
        val expected = ZonedDateTime.of(2026, 8, 6, 5, 0, 0, 0, RadikoTimeUtil.JST).toInstant()
        assertEquals(expected, dayStart)

        // 2026-08-07 12:00 JST → 当日 (8/7) の 5:00
        val jstNoon = ZonedDateTime.of(2026, 8, 7, 12, 0, 0, 0, RadikoTimeUtil.JST).toInstant()
        val dayStart2 = RadikoTimeUtil.dayStartOf(jstNoon)
        val expected2 = ZonedDateTime.of(2026, 8, 7, 5, 0, 0, 0, RadikoTimeUtil.JST).toInstant()
        assertEquals(expected2, dayStart2)
    }
}
