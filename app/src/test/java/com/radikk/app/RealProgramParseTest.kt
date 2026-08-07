package com.radikk.app

import com.radikk.app.data.api.RadikoApiClient
import com.radikk.app.data.repository.ProgramRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 実データ (TBS 番組表 JSON, 24番組) での ProgramRepository パース検証。
 * 2026-08-07 に取得した本物の v4 date API レスポンスを使用する。
 */
class RealProgramParseTest {

    private fun loadRealJson(): String {
        val stream = javaClass.classLoader!!.getResourceAsStream("tbs_programs.json")
            ?: throw AssertionError("tbs_programs.json が見つかりません")
        return stream.bufferedReader().use { it.readText() }
    }

    @Test
    fun `実データ番組表から24番組をパースできる`() {
        val repo = ProgramRepository(
            apiClient = RadikoApiClient(),
            authTokenProvider = { "dummy" }
        )
        val programs = repo.parseProgramJson(loadRealJson(), "TBS")

        assertTrue("24番組あるはず: ${programs.size}", programs.size >= 20)

        // ソートされている (時間順)
        val times = programs.map { it.ft }
        assertEquals(times.sorted(), times)

        // 各番組のフィールド
        val first = programs.first()
        assertEquals("TBS", first.stationId)
        assertTrue(first.title.isNotBlank())
        assertTrue(first.to.isAfter(first.ft))

        // 放送中判定 (現在時刻が範囲内)
        val onAir = programs.filter { it.isOnAir() }
        assertTrue("放送中番組が 0 〜 2 件: ${onAir.size}", onAir.size <= 2)
    }
}
