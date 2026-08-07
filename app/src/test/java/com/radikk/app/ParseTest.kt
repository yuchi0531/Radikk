package com.radikk.app

import com.radikk.app.data.repository.ProgramRepository
import com.radikk.app.data.repository.StationRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * XML/JSON パースの単体テスト。実データ (検証済み) を使用する。
 */
class ParseTest {

    private val sampleStationXml = """
        <?xml version="1.0" encoding="UTF-8" ?>
        <region>
          <stations ascii_name="KANTO KOSHIN" region_id="kanto-koshin" region_name="関東・甲信越">
            <station><id>TBS</id>
            <name>ＴＢＳラジオ</name>
            <ascii_name>TBS RADIO</ascii_name>
            <ruby>てぃーびーえすらじお</ruby>
            <areafree>0</areafree>
            <timefree>1</timefree>
            <logo width="224" height="100" align="center">https://radiko.jp/v2/static/station/logo/TBS/224x100.png</logo>
            <logo width="258" height="60" align="center">https://radiko.jp/v2/static/station/logo/TBS/258x60.png</logo>
            <area_id>JP13</area_id>
            <area_id>JP12</area_id>
            </station>
          </stations>
        </region>
    """.trimIndent()

    private val sampleProgramJson = """
        {
          "stations": [
            {
              "station_id": "TBS",
              "station_name": "ＴＢＳラジオ",
              "programs": {
                "program": [
                  {
                    "ft": "20260807050000",
                    "to": "20260807063000",
                    "title": "純烈・酒井一圭 BRAND-NEW MORNING",
                    "description": "テスト説明",
                    "performer": "純烈・酒井一圭",
                    "episode_id": "abc123",
                    "img": "https://program-static.cf.radiko.jp/webi/abc.jpg"
                  }
                ]
              }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `station XML をパースできる`() {
        val repo = StationRepository(com.radikk.app.data.api.RadikoApiClient())
        val stations = repo.parseStationXml(sampleStationXml)
        assertEquals(1, stations.size)
        val station = stations[0]
        assertEquals("TBS", station.id)
        assertEquals("ＴＢＳラジオ", station.name)
        assertEquals(false, station.areafree)
        assertEquals(true, station.timefree)
        assertEquals(listOf("JP13", "JP12"), station.areaIds)
        assertTrue(station.logoUrl!!.contains("TBS"))
    }

    @Test
    fun `program JSON をパースできる`() {
        val repo = ProgramRepository(
            apiClient = com.radikk.app.data.api.RadikoApiClient(),
            authTokenProvider = { "dummy" }
        )
        val programs = repo.parseProgramJson(sampleProgramJson, "TBS")
        assertEquals(1, programs.size)
        val p = programs[0]
        assertEquals("TBS", p.stationId)
        assertEquals("純烈・酒井一圭 BRAND-NEW MORNING", p.title)
        assertEquals("テスト説明", p.description)
        assertEquals("純烈・酒井一圭", p.performer)
        assertEquals("abc123", p.episodeId)
        assertTrue(p.imgUrl!!.contains("abc.jpg"))
        // JST 20260807 05:00 = UTC 20260806 20:00
        assertEquals(20, p.ft.atZone(com.radikk.app.util.RadikoTimeUtil.UTC).hour)
    }

    @Test
    fun `null 文字列の performer は null に正規化される`() {
        val repo = ProgramRepository(
            apiClient = com.radikk.app.data.api.RadikoApiClient(),
            authTokenProvider = { "dummy" }
        )
        val json = """
            {
              "stations": [
                {
                  "station_id": "TBS",
                  "programs": {
                    "program": [
                      {
                        "ft": "20260807050000",
                        "to": "20260807060000",
                        "title": "テスト番組",
                        "description": null,
                        "performer": "null",
                        "episode_id": null,
                        "img": null
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()
        val programs = repo.parseProgramJson(json, "TBS")
        assertEquals(1, programs.size)
        val p = programs[0]
        assertNull(p.performer)  // "null" 文字列が null になる
        assertNull(p.description) // JSON null が null になる
        assertNull(p.imgUrl)
    }
}
