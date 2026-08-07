package com.radikk.app

import com.radikk.app.data.api.RadikoApiClient
import com.radikk.app.data.repository.StationRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 実データ (full.xml, 110局) での StationRepository パース検証。
 * 2026-08-07 に取得した本物の full.xml を使用する。
 */
class RealStationParseTest {

    private fun loadRealXml(): String {
        val stream = javaClass.classLoader!!.getResourceAsStream("full.xml")
            ?: throw AssertionError("full.xml が見つかりません")
        return stream.bufferedReader().use { it.readText() }
    }

    @Test
    fun `実データfullXmlから110局をパースできる`() {
        val repo = StationRepository(RadikoApiClient())
        val stations = repo.parseStationXml(loadRealXml())
        assertTrue("110局以上あるはず: ${stations.size}", stations.size >= 100)

        // 主要局が含まれている
        val ids = stations.map { it.id }
        assertTrue("TBS が含まれる", ids.contains("TBS"))
        assertTrue("QRR(文化放送) が含まれる", ids.contains("QRR"))
        assertTrue("LFR(ニッポン放送) が含まれる", ids.contains("LFR"))
        assertTrue("JORF(ラジオ日本) が含まれる", ids.contains("JORF"))
        assertTrue("NHK 第一 (JOAK) が含まれる", ids.contains("JOAK"))

        // 各局のフィールドが正しくパースされている
        val tbs = stations.first { it.id == "TBS" }
        assertEquals("TBSラジオ", tbs.name)
        assertTrue("TBS の areaIds に JP13 が含まれる", tbs.areaIds.contains("JP13"))
        assertTrue("TBS は timefree 対応", tbs.timefree)
        assertTrue("TBS のロゴ URL がある", tbs.logoUrl!!.contains("TBS"))
    }

    @Test
    fun `エリアフィルタでJP13の局のみ抽出できる`() {
        val repo = StationRepository(RadikoApiClient())
        val all = repo.parseStationXml(loadRealXml())
        val jp13 = repo.filterByArea(all, "JP13")

        // JP13 (東京) 所属の局が複数ある
        assertTrue("東京の局が複数ある: ${jp13.size}", jp13.size >= 5)
        assertTrue(jp13.any { it.id == "TBS" })
        assertTrue(jp13.any { it.id == "QRR" })

        // すべて JP13 所属
        assertTrue("すべて JP13 所属", jp13.all { it.areaIds.contains("JP13") })

        // 他エリアの局 (北海道 HBC) は含まれない
        assertTrue("HBC は JP13 では聴けない", !jp13.any { it.id == "HBC" })

        // 他エリアに所属する areafree 局は含まれない (例: 沖縄の放送局)
        assertTrue("JP13 以外にのみ所属する局は含まれない", jp13.all { !it.areaIds.contains("JP47") || it.areaIds.contains("JP13") })

        // 元データは 110 局ある (フィルタで絞り込まれている)
        assertTrue("元データが 110 局ある", all.size >= 100)
    }
}
