package com.radikk.app.player

import android.util.Log
import com.radikk.app.data.api.RadikoApi
import com.radikk.app.data.api.RadikoApiClient
import com.radikk.app.util.RadikoTimeUtil
import java.io.IOException
import java.time.Instant

/**
 * ストリーム URL 解決。
 *
 * radiko の再生フロー:
 * 1. station stream XML から areafree/timefree に応じた playlist_create_url を選ぶ
 * 2. m3u8 (マスタープレイリスト) を取得 → `#EXT-X-STREAM-INF` 直後の medialist URL を抽出
 * 3. medialist URL を直接 ExoPlayer に渡す (マスタープレイリストを介さない)
 *
 * マスタープレイリストをスキップすることで、Flutter 版で発生した
 * SampleQueueMappingException (audio/mp4a-latm) の Source Error を回避する。
 */
class StreamUrlResolver(
    private val apiClient: RadikoApiClient,
) {
    companion object {
        private const val TAG = "StreamUrlResolver"

        /** 検証済みの放送局 (テスト用) */
        const val TEST_STATION = "TBS"
    }

    /**
     * ライブ再生用の m3u8 リクエスト URL を組み立てる。
     * 認証ヘッダーが必要。
     */
    fun buildLivePlaylistUrl(stationId: String, playlistCreateUrl: String, lsid: String): String =
        "$playlistCreateUrl?station_id=$stationId&l=300&type=b&lsid=$lsid"

    /**
     * タイムフリー再生用の m3u8 リクエスト URL を組み立てる。
     * ft/to は JST 14桁 (YYYYMMDDHHMMSS)。全パラメータ必須。
     * シークは番組先頭 (ft) を指す。
     */
    fun buildTimefreePlaylistUrl(
        stationId: String,
        playlistCreateUrl: String,
        from: Instant,
        to: Instant,
        lsid: String,
    ): String = buildTimefreePlaylistUrl(stationId, playlistCreateUrl, from, to, lsid, 0L)

    /**
     * タイムフリー再生用の m3u8 リクエスト URL を組み立てる。
     * ft/to は JST 14桁 (YYYYMMDDHHMMSS)。全パラメータ必須。
     *
     * radiko のタイムフリーは l=300 (約5分) のスライディングウィンドウ配信のため、
     * 番組途中へのシークは `seek` パラメータを「番組先頭 + オフセット」に設定して
     * プレイリストを作り直す必要がある (ExoPlayer.seekTo はロード済みウィンドウ内しか移動できない)。
     * サーバーは seek 位置からウィンドウを開始した medialist を返す。
     *
     * @param seekOffsetMs 番組先頭からのシーク位置 (ミリ秒)。既定 0 = 番組先頭。
     */
    fun buildTimefreePlaylistUrl(
        stationId: String,
        playlistCreateUrl: String,
        from: Instant,
        to: Instant,
        lsid: String,
        seekOffsetMs: Long,
    ): String {
        val ft = RadikoTimeUtil.formatJst14(from)
        val toJst = RadikoTimeUtil.formatJst14(to)
        val seekJst = RadikoTimeUtil.formatJst14(from.plusMillis(seekOffsetMs.coerceAtLeast(0L)))
        return "$playlistCreateUrl?station_id=$stationId&ft=$ft&to=$toJst" +
            "&start_at=$ft&end_at=$toJst&type=b&l=300&seek=$seekJst&lsid=$lsid"
    }

    /**
     * m3u8 の body から `#EXT-X-STREAM-INF` 直後の medialist URL を抽出する。
     * 見つからない場合は IOException。
     */
    fun extractMedialistUrl(m3u8Body: String): String {
        val lines = m3u8Body.lineSequence().map { it.trim() }.toList()
        for (i in lines.indices) {
            if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
                if (i + 1 < lines.size && lines[i + 1].isNotBlank() && !lines[i + 1].startsWith("#")) {
                    return lines[i + 1]
                }
            }
        }
        // フォールバック: レスポンスがすでに medialist 形式 (#EXTINF を含む) の場合、
        // 最初の http(s) URL をそのまま medialist URL として使う
        lines.firstOrNull { it.startsWith("http") }?.let { return it }
        throw IOException("マスタープレイリストから medialist URL を抽出できませんでした")
    }

    /**
     * medialist の body から全セグメント URL を抽出する。
     * #EXTINF: に続く行がセグメント URL。コメント行・空行・マスタープレイリスト行をスキップ。
     * 実データではセグメント行は http(s) で始まるため、#EXTINF が無くても
     * http で始まる行はセグメントとして扱う (堅牢性のためのフォールバック)。
     * @return セグメント URL のリスト (URL 相対の場合は medialist ベースで解決)
     */
    fun extractSegmentUrls(medialistBody: String, medialistUrl: String): List<String> {
        val lines = medialistBody.lineSequence().map { it.trim() }.toList()
        val segments = mutableListOf<String>()
        var lastWasExtinf = false
        for (line in lines) {
            if (line.isEmpty() || line.startsWith("#")) {
                // #EXTINF: に続く行がセグメント URL
                lastWasExtinf = line.startsWith("#EXTINF:")
                continue
            }
            // セグメント URL (絶対 URL、または相対パス / プロトコル相対 // / クエリ相対 ?)
            if (lastWasExtinf || line.startsWith("http")) {
                resolveSegmentUrl(medialistUrl, line)?.let { segments.add(it) }
            }
            lastWasExtinf = false
        }
        return segments
    }

    /**
     * セグメント行を絶対 URL に解決する。
     * - http(s) で始まる行はそのまま
     * - それ以外は [java.net.URI.resolve] で解決する (RFC 3986 準拠)。
     *   `//host/...` (プロトコル相対)、`?query` (クエリ相対)、`path/seg.aac` (パス相対) を正しく扱える。
     * - 解決できない行は null (呼び出し側でスキップ)
     */
    private fun resolveSegmentUrl(medialistUrl: String, line: String): String? {
        if (line.startsWith("http")) return line
        return runCatching { java.net.URI(medialistUrl).resolve(line).toString() }.getOrNull()
    }

    /**
     * station stream XML からライブ用 playlist_create_url の候補リストを取得する。
     * ライブ用 (timefree="0") の URL を全て返す (優先順位付き)。
     *
     * 通常の局は areafree="1" (地域フリー) の URL を使う。しかし NHK などの局では
     * areafree="1" の smartstream 系 (si-c) が HTTP 504 を返し、areafree="0" の
     * dr-wowza のみ正常に配信される。そのため areafree に関わらず timefree="0" の
     * 候補を列挙し、dr-wowza を優先して呼び出し側で順に試行 (フォールバック) する。
     */
    suspend fun getLivePlaylistUrls(stationId: String): List<String> {
        val xml = apiClient.getString(RadikoApi.STATION_STREAM_URL + stationId + ".xml")
        return extractPlaylistUrls(xml, areafree = null, timefree = false)
    }

    /**
     * station stream XML からタイムフリー用 playlist_create_url を取得する。
     * areafree="0" かつ timefree="1" の URL を選択。
     */
    suspend fun getTimefreePlaylistUrl(stationId: String): String {
        val xml = apiClient.getString(RadikoApi.STATION_STREAM_URL + stationId + ".xml")
        return extractPlaylistUrl(xml, areafree = false, timefree = true)
    }

    /**
     * station stream XML をパースして playlist_create_url を抽出する。
     * <url areafree="0|1" max_delay="60" timefree="0|1"><playlist_create_url>...</...>
     * 属性の順序に依存しない (areafree/timefree を個別に検索)。
     */
    internal fun extractPlaylistUrl(xml: String, areafree: Boolean, timefree: Boolean): String {
        return extractPlaylistUrls(xml, areafree, timefree).firstOrNull()
            ?: throw IOException("station XML から playlist_create_url を見つけられませんでした (areafree=$areafree, timefree=$timefree)")
    }

    /**
     * station stream XML をパースして playlist_create_url の候補リストを抽出する。
     * @param areafree null の場合は areafree 属性を無視して全ての候補を返す (ライブのフォールバック用)。
     * dr-wowza を優先する (NHK 等で smartstream が 504 を返す問題の回避)。
     */
    internal fun extractPlaylistUrls(
        xml: String,
        areafree: Boolean?,
        timefree: Boolean,
    ): List<String> {
        // <url ...> ブロックごとに areafree/timefree 属性と playlist_create_url を抽出
        val urlBlockRegex = Regex("""<url\b([^>]*)>\s*<playlist_create_url>([^<]+)</playlist_create_url>""")
        val targetAreafree = if (areafree == null) null else if (areafree) "1" else "0"
        val targetTimefree = if (timefree) "1" else "0"

        // 候補を収集 (URL と areafree 属性を保持)
        data class Candidate(val url: String, val areafree: Int)
        val matched = mutableListOf<Candidate>()
        for (match in urlBlockRegex.findAll(xml)) {
            val attrs = match.groupValues[1]
            val url = match.groupValues[2]
            val urlAreafree = Regex("""areafree="(\d+)"""").find(attrs)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val urlTimefree = Regex("""timefree="(\d+)"""").find(attrs)?.groupValues?.get(1)
            val areafreeOk = targetAreafree == null || urlAreafree == targetAreafree.toIntOrNull()
            if (areafreeOk && urlTimefree == targetTimefree) {
                matched.add(Candidate(url, urlAreafree))
            }
        }
        // 優先順位:
        //   1. areafree=1 (地域フリー) を先に試す (一般局はここで成功する)
        //   2. areafree=0 (地域固定) はフォールバック (NHK 等で si-c が 504 のとき使う)
        // 各グループ内では dr-wowza → smartstream → その他 の順
        return matched.sortedWith(
            compareBy({ it.areafree == 0 }, { playlistPriority(it.url) }),
        ).map { it.url }
    }

    private fun playlistPriority(url: String): Int = when {
        url.contains("dr-wowza") -> 0
        url.contains("smartstream") -> 1
        else -> 2
    }

    /**
     * ライブ再生用の medialist URL を取得する。
     * 候補 URL を優先順位で順に試し、成功した最初の medialist URL を返す。
     * @param token 認証トークン (X-Radiko-AuthToken)
     */
    suspend fun resolveLiveMedialistUrl(
        stationId: String,
        token: String,
        lsid: String = RadikoApi.randomHex32(),
    ): String {
        val playlistUrls = getLivePlaylistUrls(stationId)
        var lastError: Exception? = null
        for (playlistUrl in playlistUrls) {
            val m3u8Url = buildLivePlaylistUrl(stationId, playlistUrl, lsid)
            try {
                return resolveMedialist(m3u8Url, token)
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "ライブ playlist 失敗 (${playlistUrl}): ${e.message}")
            }
        }
        throw lastError ?: IOException("ライブ再生用の playlist URL が見つかりませんでした")
    }

    /**
     * タイムフリー再生用の medialist URL を取得する。
     * @param seekOffsetMs 番組先頭からのシーク位置 (ミリ秒)。既定 0 = 番組先頭。
     *                     シーク時はプレイリストを seek 位置で作り直し、そこから配信される。
     */
    suspend fun resolveTimefreeMedialistUrl(
        stationId: String,
        token: String,
        from: Instant,
        to: Instant,
        lsid: String = RadikoApi.randomHex32(),
        seekOffsetMs: Long = 0L,
    ): String {
        val playlistUrl = getTimefreePlaylistUrl(stationId)
        val m3u8Url = buildTimefreePlaylistUrl(stationId, playlistUrl, from, to, lsid, seekOffsetMs)
        return resolveMedialist(m3u8Url, token)
    }

    private suspend fun resolveMedialist(m3u8Url: String, token: String): String {
        val headers = mapOf("X-Radiko-AuthToken" to token)
        val body = apiClient.getString(m3u8Url, headers)
        Log.d(TAG, "m3u8 (${m3u8Url.take(80)}...): ${body.take(200)}")
        val medialistUrl = extractMedialistUrl(body)

        // medialist URL 自体を GET して 200 が返ることを確認する。
        // NHK などでは si-c の medialist が HTTP 504 を返す (m3u8 は成功する)。
        // ここで検証して失敗したら呼び出し側のフォールバックに委ねる。
        val checkBody = apiClient.getString(medialistUrl, headers)
        Log.d(TAG, "medialist (${medialistUrl.take(80)}...): ${checkBody.take(120)}")
        return medialistUrl
    }
}
