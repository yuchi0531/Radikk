package com.radikk.app.data.api

import java.security.MessageDigest
import kotlin.random.Random

/**
 * radiko API の定数と端末情報。
 *
 * 検証済み仕様 (docs/radikk-kotlin-rebuild-prompt.md):
 * - auth1/auth2 では X-Radiko-App/App-Version/Device/User が必須
 * - App-Version は auth1/auth2 間で同じ値を使う (変えると 401)
 * - Device / User は起動間で固定し永続化する
 * - fullKey は assets/fullkey.b64 に格納 (167KB base64)
 */
object RadikoApi {
    // --- エンドポイント ---
    const val AUTH1_URL = "https://api.radiko.jp/v2/api/auth1"
    const val AUTH2_URL = "https://api.radiko.jp/v2/api/auth2"
    const val STATION_REGION_URL = "https://radiko.jp/v3/station/region/full.xml"
    const val STATION_STREAM_URL = "https://radiko.jp/v3/station/stream/pc_html5/"
    const val PROGRAM_DATE_URL = "https://api.radiko.jp/program/v4/date/"

    // --- 認証ヘッダー ---
    const val APP = "aSmartPhone8"
    const val APP_VERSION = "8.4.5"

    /** 検証済みの固定デバイス ID (実機検証で認証が通る値) */
    const val DEFAULT_DEVICE = "33.Pixel 8"

    /** エリアごとの代表 GPS 座標 (都道府県庁所在地)。X-Radiko-Location 用 */
    val AREA_COORDS: Map<String, Pair<Double, Double>> = mapOf(
        "JP1" to (43.064615 to 141.346807), "JP2" to (40.824308 to 140.739998),
        "JP3" to (39.703619 to 141.152684), "JP4" to (38.268837 to 140.8721),
        "JP5" to (39.718614 to 140.102364), "JP6" to (38.240436 to 140.363633),
        "JP7" to (37.750299 to 140.467551), "JP8" to (36.341811 to 140.446793),
        "JP9" to (36.565725 to 139.883565), "JP10" to (36.390668 to 139.060406),
        "JP11" to (35.856999 to 139.648849), "JP12" to (35.605057 to 140.123306),
        "JP13" to (35.689488 to 139.691706), "JP14" to (35.447507 to 139.642345),
        "JP15" to (37.902552 to 139.023095), "JP16" to (36.695291 to 137.211338),
        "JP17" to (36.594682 to 136.625573), "JP18" to (36.065178 to 136.221527),
        "JP19" to (35.664158 to 138.568449), "JP20" to (36.651299 to 138.180956),
        "JP21" to (35.391227 to 136.722291), "JP22" to (34.97712 to 138.383084),
        "JP23" to (35.180188 to 136.906565), "JP24" to (34.730283 to 136.508588),
        "JP25" to (35.004531 to 135.86859), "JP26" to (35.021247 to 135.755597),
        "JP27" to (34.686297 to 135.519661), "JP28" to (34.691269 to 135.183071),
        "JP29" to (34.685334 to 135.832742), "JP30" to (34.225987 to 135.167509),
        "JP31" to (35.503891 to 134.237736), "JP32" to (35.472295 to 133.0505),
        "JP33" to (34.661751 to 133.934406), "JP34" to (34.39656 to 132.459622),
        "JP35" to (34.185956 to 131.470649), "JP36" to (34.065718 to 134.55936),
        "JP37" to (34.340149 to 134.043444), "JP38" to (33.841624 to 132.765681),
        "JP39" to (33.559706 to 133.531079), "JP40" to (33.606576 to 130.418297),
        "JP41" to (33.249442 to 130.299794), "JP42" to (32.744839 to 129.873756),
        "JP43" to (32.789827 to 130.741667), "JP44" to (33.238172 to 131.612619),
        "JP45" to (31.911096 to 131.423893), "JP46" to (31.560146 to 130.557978),
        "JP47" to (26.2124 to 127.680932),
    )

    /** エリア ID → 都道府県名 */
    val AREA_NAMES: Map<String, String> = mapOf(
        "JP1" to "北海道", "JP2" to "青森県", "JP3" to "岩手県", "JP4" to "宮城県",
        "JP5" to "秋田県", "JP6" to "山形県", "JP7" to "福島県", "JP8" to "茨城県",
        "JP9" to "栃木県", "JP10" to "群馬県", "JP11" to "埼玉県", "JP12" to "千葉県",
        "JP13" to "東京都", "JP14" to "神奈川県", "JP15" to "新潟県", "JP16" to "富山県",
        "JP17" to "石川県", "JP18" to "福井県", "JP19" to "山梨県", "JP20" to "長野県",
        "JP21" to "岐阜県", "JP22" to "静岡県", "JP23" to "愛知県", "JP24" to "三重県",
        "JP25" to "滋賀県", "JP26" to "京都府", "JP27" to "大阪府", "JP28" to "兵庫県",
        "JP29" to "奈良県", "JP30" to "和歌山県", "JP31" to "鳥取県", "JP32" to "島根県",
        "JP33" to "岡山県", "JP34" to "広島県", "JP35" to "山口県", "JP36" to "徳島県",
        "JP37" to "香川県", "JP38" to "愛媛県", "JP39" to "高知県", "JP40" to "福岡県",
        "JP41" to "佐賀県", "JP42" to "長崎県", "JP43" to "熊本県", "JP44" to "大分県",
        "JP45" to "宮崎県", "JP46" to "鹿児島県", "JP47" to "沖縄県",
    )

    /** エリア選択用の ID 順リスト */
    val AREA_IDS: List<String> = (1..47).map { "JP$it" }

    /**
     * ユーザー ID (32文字 hex) をシードから生成する。
     * シードが同じなら同じ値を返す (起動間で固定に使う)。
     */
    fun userId(seed: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(seed.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** ランダムな 32文字 16進数 (lsid 用) */
    fun randomHex32(): String {
        val bytes = ByteArray(16)
        Random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
