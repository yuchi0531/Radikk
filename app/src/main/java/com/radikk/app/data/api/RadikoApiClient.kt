package com.radikk.app.data.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OkHttp を使った radiko API クライアント。
 *
 * 用途ごとに 2 つのクライアントを使い分ける:
 * - authClient: 認証 (api.radiko.jp)。auth1/auth2 共通ヘッダーを持つ
 * - webClient: 一般 (radiko.jp)。認証ヘッダー不要 (station XML, 番組表など)
 *
 * 注意: 認証ヘッダー (X-Radiko-App 等) は auth1/auth2 のみに付与する。
 * その他の API に付けると問題になるケースがあるため分離している。
 *
 * 全メソッドは suspend。ネットワーク IO は Dispatchers.IO で実行し、
 * メインスレッドをブロックしない (ANR 防止)。
 */
class RadikoApiClient {

    companion object {
        private const val TAG = "RadikoApiClient"
        private const val TIMEOUT_SECONDS = 30L

        /** 検証済みの UA (radiko 公式アプリと同等) */
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; Pixel 4 XL) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/80.0.3987.87 Mobile Safari/537.36"
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** auth1/auth2 用クライアント (認証ヘッダー付き) */
    private val authClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * GET リクエストを実行する (Dispatchers.IO)。
     * @param authHeaders 追加で送るヘッダー (auth1 用の X-Radiko-* など)
     */
    suspend fun get(url: String, authHeaders: Map<String, String> = emptyMap()): Response =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", USER_AGENT)
            authHeaders.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute()
        }

    /**
     * GET リクエストを認証用クライアントで実行する (Dispatchers.IO)。
     * auth1/auth2 用。
     */
    suspend fun getAuth(url: String, authHeaders: Map<String, String>): Response =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", USER_AGENT)
            authHeaders.forEach { (k, v) -> builder.header(k, v) }
            authClient.newCall(builder.build()).execute()
        }

    /**
     * GET で文字列 body を取得する。非 2xx なら例外。
     */
    suspend fun getString(url: String, authHeaders: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            get(url, authHeaders).use { resp ->
                if (!resp.isSuccessful) {
                    val body = resp.body?.string()?.take(300) ?: ""
                    Log.w(TAG, "GET $url -> ${resp.code}: $body")
                    throw IOException("HTTP ${resp.code}: $body")
                }
                resp.body?.string() ?: ""
            }
        }

    /**
     * GET でバイト配列を取得する。
     */
    suspend fun getBytes(url: String, authHeaders: Map<String, String> = emptyMap()): ByteArray =
        withContext(Dispatchers.IO) {
            get(url, authHeaders).use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("HTTP ${resp.code}")
                }
                resp.body?.bytes() ?: ByteArray(0)
            }
        }
}
