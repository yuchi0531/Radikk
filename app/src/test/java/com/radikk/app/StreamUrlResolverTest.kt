package com.radikk.app

import com.radikk.app.player.StreamUrlResolver
import com.radikk.app.data.api.RadikoApiClient
import com.radikk.app.util.RadikoTimeUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZonedDateTime

/**
 * StreamUrlResolver の単体テスト。
 * 実データ (検証済み) をサンプルとして使用する。
 */
class StreamUrlResolverTest {

    private val resolver = StreamUrlResolver(RadikoApiClient())

    // 実機検証で取得した station XML (属性順が様々なパターン)
    private val sampleStationXml = """
        <?xml version="1.0" encoding="UTF-8" ?>
        <urls>
          <url areafree="0" max_delay="60" timefree="0">
            <playlist_create_url>https://si-f-radiko.smartstream.ne.jp/so/playlist.m3u8</playlist_create_url>
          </url>
          <url areafree="0" max_delay="60" timefree="1">
            <playlist_create_url>https://tf-f-rpaa-radiko.smartstream.ne.jp/tf/playlist.m3u8</playlist_create_url>
          </url>
          <url areafree="1" max_delay="60" timefree="0">
            <playlist_create_url>https://si-c-radiko.smartstream.ne.jp/so/playlist.m3u8</playlist_create_url>
          </url>
          <url areafree="1" max_delay="60" timefree="1">
            <playlist_create_url>https://tf-c-rpaa-radiko.smartstream.ne.jp/tf/playlist.m3u8</playlist_create_url>
          </url>
        </urls>
    """.trimIndent()

    // 実機検証で取得したマスタープレイリスト
    private val sampleMasterPlaylist = """
        #EXTM3U
        #EXT-X-VERSION:6
        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=52973,CODECS="mp4a.40.5"
        https://si-c-radiko.smartstream.ne.jp/medialist?session=3.XgfZan52KeqnAboWfzuga7&station_id=TBS&cython=1
    """.trimIndent()

    @Test
    fun `ライブ用 playlist_create_url を抽出する`() {
        val url = resolver.extractPlaylistUrl(sampleStationXml, areafree = true, timefree = false)
        assertEquals("https://si-c-radiko.smartstream.ne.jp/so/playlist.m3u8", url)
    }

    @Test
    fun `タイムフリー用 playlist_create_url を抽出する`() {
        val url = resolver.extractPlaylistUrl(sampleStationXml, areafree = false, timefree = true)
        assertEquals("https://tf-f-rpaa-radiko.smartstream.ne.jp/tf/playlist.m3u8", url)
    }

    @Test
    fun `medialist URL をマスタープレイリストから抽出する`() {
        val url = resolver.extractMedialistUrl(sampleMasterPlaylist)
        assertTrue(url.startsWith("https://si-c-radiko.smartstream.ne.jp/medialist?session="))
        assertTrue(url.contains("station_id=TBS"))
    }

    @Test
    fun `属性順が逆でも playlist_create_url を抽出できる`() {
        // max_delay が先頭、timefree が areafree より先に来るケース
        val xml = """
            <urls>
              <url max_delay="60" timefree="1" areafree="0">
                <playlist_create_url>https://tf-f-rpaa-radiko.smartstream.ne.jp/tf/playlist.m3u8</playlist_create_url>
              </url>
              <url max_delay="60" timefree="0" areafree="1">
                <playlist_create_url>https://si-c-radiko.smartstream.ne.jp/so/playlist.m3u8</playlist_create_url>
              </url>
            </urls>
        """.trimIndent()
        val live = resolver.extractPlaylistUrl(xml, areafree = true, timefree = false)
        assertEquals("https://si-c-radiko.smartstream.ne.jp/so/playlist.m3u8", live)
        val tf = resolver.extractPlaylistUrl(xml, areafree = false, timefree = true)
        assertEquals("https://tf-f-rpaa-radiko.smartstream.ne.jp/tf/playlist.m3u8", tf)
    }

    @Test
    fun `medialist が直接返った場合も URL を抽出する`() {
        val medialistDirect = """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-TARGETDURATION:5
            #EXTINF:5.035,
            https://si-c-radiko.smartstream.ne.jp/segments/o/B/TBS/20260807/x.aac
        """.trimIndent()
        val url = resolver.extractMedialistUrl(medialistDirect)
        assertTrue(url.startsWith("https://si-c-radiko.smartstream.ne.jp/segments/o/B/TBS/"))
    }

    @Test
    fun `ライブ m3u8 URL を組み立てる`() {
        val url = resolver.buildLivePlaylistUrl(
            "TBS",
            "https://si-c-radiko.smartstream.ne.jp/so/playlist.m3u8",
            "0123456789abcdef0123456789abcdef"
        )
        assertEquals(
            "https://si-c-radiko.smartstream.ne.jp/so/playlist.m3u8?station_id=TBS&l=300&type=b&lsid=0123456789abcdef0123456789abcdef",
            url
        )
    }

    @Test
    fun `タイムフリー m3u8 URL を組み立てる`() {
        val ft = ZonedDateTime.of(2026, 8, 7, 12, 30, 0, 0, RadikoTimeUtil.JST).toInstant()
        val to = ZonedDateTime.of(2026, 8, 7, 13, 0, 0, 0, RadikoTimeUtil.JST).toInstant()
        val url = resolver.buildTimefreePlaylistUrl(
            "TBS",
            "https://tf-f-rpaa-radiko.smartstream.ne.jp/tf/playlist.m3u8",
            ft,
            to,
            "0123456789abcdef0123456789abcdef"
        )
        assertEquals(
            "https://tf-f-rpaa-radiko.smartstream.ne.jp/tf/playlist.m3u8?station_id=TBS" +
                "&ft=20260807123000&to=20260807130000" +
                "&start_at=20260807123000&end_at=20260807130000" +
                "&type=b&l=300&seek=20260807123000&lsid=0123456789abcdef0123456789abcdef",
            url
        )
    }
}
