package com.radikk.app.data.download

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.radikk.app.data.api.RadikoApiClient
import com.radikk.app.player.StreamUrlResolver
import com.radikk.app.util.RadikoTimeUtil
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import kotlinx.coroutines.delay

/**
 * タイムフリー番組のダウンロード実行。
 *
 * radiko のタイムフリーは l=300 (約5分) のスライディングウィンドウ配信のため、
 * medialist を 1 回取得しただけでは番組の約5分分のセグメントしか得られない。
 * そこでシーク位置を [WINDOW_MS] ずつ進めて medialist を取得し直しながら
 * 番組全体のセグメント URL を収集する (ウィンドウループ)。
 *
 * フロー:
 * 1. タイムフリー medialist URL を解決 (最初のウィンドウ: seek=番組先頭)
 * 2. medialist body からセグメント URL を抽出
 * 3. シーク位置を [WINDOW_MS] ずつ進めて再解決・再取得し、番組終了まで繰り返す
 * 4. ウィンドウ境界で重複し得るセグメントを URL で重複排除 (順序保持)
 * 5. 各セグメントを順番に取得し、先頭の ID3v2 タグを除去して単一の .aac に連結して書き出す
 *
 * 書き出し先は 2 通り:
 * - [targetTreeUri]: SAF の tree Uri (フォルダ選択で取得)。DocumentFile +
 *   contentResolver.openOutputStream で書き出す (MANAGE_EXTERNAL_STORAGE 不要)。
 * - [targetDir]: 通常のファイルシステム上のディレクトリ (アプリ固有領域など)。
 *
 * 注意: 認証トークン (X-Radiko-AuthToken) は medialist / セグメント取得時に必要。
 * トークンは約90分で失効するため、長い番組では取得途中で 401 になり得る。
 * 401 が発生した場合は [freshTokenProvider] で新トークンを取得し、その
 * ウィンドウ / セグメントを 1 回だけ再試行する (それでも失敗した場合は
 * [mapAuthError] で明確なメッセージに置き換える)。
 * ダウンロードは逐次実行のみ (サーバー負荷を抑えるため並列化しない)。
 */
class DownloadManager(
    private val apiClient: RadikoApiClient,
    private val resolver: StreamUrlResolver,
    private val repo: DownloadRepository,
    private val freshTokenProvider: (suspend () -> String?)? = null,
) {

    companion object {
        private const val TAG = "DownloadManager"

        /** radiko のタイムフリー配信ウィンドウ幅 (l=300 = 約5分)。 */
        const val WINDOW_MS = 300_000L

        /**
         * ウィンドウループで「新しいセグメントが得られない」状態を許容する回数。
         * ウィンドウ境界では同一 URL が重複し得るため、1 回の無進歩は正常だが、
         * これを超えて続く場合は配信が途中で止まったと判断する。
         */
        private const val MAX_CONSECUTIVE_NO_PROGRESS_WINDOWS = 2

        /**
         * セグメント取得でリトライする 5xx エラーの HTTP ステータスコード。
         */
        private val RETRYABLE_5XX = setOf(500, 502, 503, 504)

        /**
         * セグメント取得の 5xx リトライ回数。
         */
        private const val SEGMENT_5XX_RETRY_COUNT = 2

        /**
         * セグメント取得の 5xx リトライ間隔 (ミリ秒)。
         */
        private const val SEGMENT_5XX_RETRY_DELAY_MS = 1_000L

        /**
         * セグメント先頭の ID3v2 タグを除去して純粋な ADTS AAC だけを返す。
         * radiko の .aac セグメントは「ID3v2 タグ + ADTS」の構成。
         * ID3v2 ヘッダーは "ID3" + バージョン2B + フラグ1B + サイズ4B (syncsafe)。
         * ヘッダー10B + サイズ分をスキップする。
         *
         * ヘッダーは存在するがタグ本体が途中で切れている (skip > data.size) 場合は
         * 不完全なセグメントとして空を返す。元データを返すと ID3 が残ったままになり
         * ADTS フレームが壊れて再生に失敗するため。
         */
        internal fun stripId3(data: ByteArray): ByteArray {
            if (data.size < 10) return data
            if (data[0] == 'I'.code.toByte() && data[1] == 'D'.code.toByte() && data[2] == '3'.code.toByte()) {
                val size = ((data[6].toInt() and 0x7F) shl 21) or
                    ((data[7].toInt() and 0x7F) shl 14) or
                    ((data[8].toInt() and 0x7F) shl 7) or
                    (data[9].toInt() and 0x7F)
                val skip = 10 + size
                if (skip > 0 && skip <= data.size) {
                    return data.copyOfRange(skip, data.size)
                }
                // タグサイズがデータ全体を超える = ヘッダーは切れているが本体が欠落した
                // 不完全なセグメント。元データを返すと ADTS フレーミングが壊れるため空を返す。
                if (skip > data.size) {
                    return ByteArray(0)
                }
            }
            return data
        }
    }

    /**
     * ダウンロード中に 401 (トークン失効) で新トークンに差し替わった場合、
     * その後のウィンドウ / セグメント取得すべてに新しいトークンを使うための
     * ミュータブルなトークン保持クラス。
     */
    private class TokenRef(initial: String) {
        var token: String = initial

        fun headers(): Map<String, String> = mapOf(HEADER_NAME to token)

        companion object {
            const val HEADER_NAME = "X-Radiko-AuthToken"
        }
    }

    /**
     * タイムフリー番組を単一 .aac ファイルとしてダウンロードして DownloadRepository に登録する。
     *
     * @param targetDir ファイルシステム上の出力ディレクトリ。[targetTreeUri] が null のときに使う。
     * @param targetTreeUri SAF の tree Uri (ユーザーがフォルダ選択で選んだもの)。
     *                      null でなければこちらへ書き出す (DocumentFile 経由)。
     * @param context [targetTreeUri] を使う場合に必要 (contentResolver 解決用)。
     * @param imgUrl 番組ロゴ URL (フルプレイヤー表示用)。無ければ null。
     * @param description 番組の説明文 (フルプレイヤー表示用)。無ければ null。
     * @param performer 番組の出演者 (フルプレイヤー表示用)。無ければ null。
     * @param onProgress 0.0〜1.0 の進捗コールバック (セグメント書き込みごとに呼ばれる)
     * @param freshTokenProvider 取得途中でトークンが失効した場合 (401) に呼ばれる
     *                           新トークン供給コールバック。null なら [mapAuthError] に委ねる。
     *                           未指定ならコンストラクタの既定を使う。
     * @return 登録された [DownloadedProgram]
     * @throws IOException メディアリスト解決失敗・セグメント取得失敗・セグメントが 0 個・書き込み先が不正な場合
     */
    suspend fun downloadProgram(
        stationId: String,
        stationName: String,
        programTitle: String,
        ft: Instant,
        to: Instant,
        token: String,
        targetDir: File? = null,
        targetTreeUri: Uri? = null,
        context: Context? = null,
        imgUrl: String? = null,
        description: String? = null,
        performer: String? = null,
        onProgress: (Float) -> Unit = {},
        freshTokenProvider: (suspend () -> String?)? = null,
    ): DownloadedProgram {
        // 呼び出しごとのプロバイダを優先し、無ければコンストラクタの既定を使う
        val tokenProvider = freshTokenProvider ?: this.freshTokenProvider
        // 401 でトークンが差し替わった場合に全ウィンドウ / セグメントへ反映するため、
        // 固定の headers ではなくミュータブルな TokenRef を共有する。
        val tokenRef = TokenRef(token)
        val durationMs = (to.toEpochMilli() - ft.toEpochMilli()).coerceAtLeast(0L)

        // 1-4. ウィンドウループで番組全体のセグメント URL を収集
        val segments = try {
            collectAllSegmentUrls(stationId, ft, to, durationMs, tokenRef, tokenProvider)
        } catch (e: Exception) {
            throw mapAuthError(e)
        }

        // 5. セグメントが 0 個ならエラー
        if (segments.isEmpty()) {
            throw IOException("ダウンロード可能なセグメントがありません")
        }

        // 6. セグメントを連結して単一 .aac ファイルとして保存
        //    ファイル名はファイルシステム上で安全な `${stationId}_${JST14}.aac` を使う
        val fileName = "${stationId}_${RadikoTimeUtil.formatJst14(ft)}.aac"
        val filePath: String

        if (targetTreeUri != null && context != null) {
            // SAF tree Uri 経由 (ユーザー選択フォルダ)
            val parent = DocumentFile.fromTreeUri(context, targetTreeUri)
                ?: throw IOException("ダウンロード先フォルダを開けませんでした")
            val doc = parent.createFile("audio/aac", fileName)
                ?: throw IOException("ダウンロード先にファイルを作成できませんでした")
            try {
                context.contentResolver.openOutputStream(doc.uri)?.use { out ->
                    writeSegments(out, segments, tokenRef, onProgress, tokenProvider)
                } ?: throw IOException("出力ストリームを開けませんでした")
            } catch (e: Exception) {
                // 失敗時は部分ファイルを削除して再スローする
                runCatching { doc.delete() }
                throw mapAuthError(e)
            }
            filePath = doc.uri.toString()
        } else {
            // 通常のファイルシステム
            val dir = targetDir ?: throw IOException("ダウンロード先が指定されていません")
            dir.mkdirs()
            val outputFile = File(dir, fileName)
            try {
                FileOutputStream(outputFile).use { out ->
                    writeSegments(out, segments, tokenRef, onProgress, tokenProvider)
                }
            } catch (e: Exception) {
                // 失敗時は部分ファイルを削除して再スローする
                runCatching { outputFile.delete() }
                throw mapAuthError(e)
            }
            filePath = outputFile.absolutePath
        }

        // 7. リポジトリに登録
        val entry = DownloadedProgram(
            stationId = stationId,
            stationName = stationName,
            programTitle = programTitle,
            ftEpochMillis = ft.toEpochMilli(),
            toEpochMillis = to.toEpochMilli(),
            filePath = filePath,
            downloadedAtEpochMillis = System.currentTimeMillis(),
            imgUrl = imgUrl,
            description = description,
            performer = performer,
        )
        repo.add(entry)
        return entry
    }

    /**
     * スライディングウィンドウ (l=300) の medialist をシーク位置をずらしながら
     * 繰り返し取得し、番組全体のセグメント URL を収集する。
     *
     * 1 回の medialist では約5分分のセグメントしか得られないため、シーク位置を
     * [WINDOW_MS] ずつ進めて「番組先頭 + オフセット」の seek パラメータ付きで
     * プレイリストを作り直す。ウィンドウ境界では同じ URL が重複し得るため、
     * [LinkedHashSet] で URL 重複排除する (出現順は保持)。
     *
     * 終了条件:
     * - 番組終了 (durationMs) に達し、最後のウィンドウが進捗を得た
     * - 番組終了前に「新しいセグメントが得られない」ウィンドウが [MAX_CONSECUTIVE_NO_PROGRESS_WINDOWS]
     *   回連続した → 配信が途中で止まったと判断して IOException
     *   (ウィンドウ境界では同じ URL が重複し得るため 1 回の無進歩は許容する。
     *   最後のウィンドウが無進歩のまま番組終了に達した場合も末尾欠落としてエラーにする)
     */
    private suspend fun collectAllSegmentUrls(
        stationId: String,
        ft: Instant,
        to: Instant,
        durationMs: Long,
        tokenRef: TokenRef,
        freshTokenProvider: (suspend () -> String?)?,
    ): List<String> {
        val allSegments = LinkedHashSet<String>()
        var windowStartMs = 0L
        var consecutiveNoProgress = 0
        while (true) {
            val previousCount = allSegments.size
            val (medialistUrl, body) = fetchWindowBody(stationId, ft, to, windowStartMs, tokenRef, freshTokenProvider)
            allSegments.addAll(resolver.extractSegmentUrls(body, medialistUrl))

            if (allSegments.size == previousCount) {
                // ウィンドウ境界では同じ URL が重複し得るため、1 回の無進歩は許容する。
                // 連続して無進歩なら配信が途中で止まったと判断する。
                consecutiveNoProgress++
                Log.w(
                    TAG,
                    "ウィンドウ $windowStartMs で新しいセグメントが得られませんでした " +
                        "($consecutiveNoProgress/${MAX_CONSECUTIVE_NO_PROGRESS_WINDOWS} 回)",
                )
            } else {
                consecutiveNoProgress = 0
            }

            // 番組終了まで進み、最後のウィンドウが正常に進捗していれば完了。
            // 最後のウィンドウが無進歩のまま番組終了に達した場合は、末尾が欠落して
            // いる可能性が高いため、下の確認でエラーにする (黙って切り捨てない)。
            if (windowStartMs + WINDOW_MS >= durationMs && consecutiveNoProgress == 0) break

            // 無進歩が連続したら末尾欠落としてエラー
            if (consecutiveNoProgress >= MAX_CONSECUTIVE_NO_PROGRESS_WINDOWS) {
                throw IOException(
                    "セグメント収集が途中で停止しました " +
                        "(ウィンドウ ${windowStartMs / 1000}s, ${allSegments.size} セグメント)",
                )
            }

            windowStartMs += WINDOW_MS
        }
        return allSegments.toList()
    }

    /**
     * 1 ウィンドウ分の medialist を取得する。
     * 401 (トークン失効) の場合は [freshTokenProvider] で新トークンを取得して
     * [tokenRef] に反映した上で、そのウィンドウだけ 1 回再試行する。
     * 再試行が無い / 失敗した場合は元の例外を投げる。
     * 5xx (500/502/503/504) は短い間隔でリトライする。
     * @return medialist URL と body のペア (URL 相対のセグメント解決に medialist URL が必要なため)
     */
    private suspend fun fetchWindowBody(
        stationId: String,
        ft: Instant,
        to: Instant,
        seekOffsetMs: Long,
        tokenRef: TokenRef,
        freshTokenProvider: (suspend () -> String?)?,
    ): Pair<String, String> {
        try {
            val url = resolver.resolveTimefreeMedialistUrl(stationId, tokenRef.token, ft, to, seekOffsetMs = seekOffsetMs)
            return url to apiClient.getString(url, tokenRef.headers())
        } catch (e: Exception) {
            if (isAuth401(e) && freshTokenProvider != null) {
                val newToken = freshTokenProvider() ?: throw e
                // 新しいトークンを共有 TokenRef に反映して、以後の全取得で使う
                tokenRef.token = newToken
                val url = resolver.resolveTimefreeMedialistUrl(stationId, newToken, ft, to, seekOffsetMs = seekOffsetMs)
                return url to apiClient.getString(url, tokenRef.headers())
            }
            throw e
        }
    }

    /**
     * 1 セグメント分のバイト列を取得する。
     * 401 (トークン失効) の場合は [freshTokenProvider] で新トークンを取得して
     * [tokenRef] に反映した上で、そのセグメントだけ 1 回再試行する。
     * 5xx (500/502/503/504) は短い間隔で数回リトライする。
     * リトライを使い切った場合は最後のエラーをそのまま投げる。
     */
    private suspend fun fetchSegmentBytes(
        url: String,
        tokenRef: TokenRef,
        freshTokenProvider: (suspend () -> String?)?,
    ): ByteArray {
        var lastError: Exception? = null
        var attempts = 0
        // 5xx は一時的なサーバー障害の可能性が高いため、短い間隔で数回リトライする
        while (attempts <= SEGMENT_5XX_RETRY_COUNT) {
            attempts++
            try {
                return apiClient.getBytes(url, tokenRef.headers())
            } catch (e: Exception) {
                if (isAuth401(e) && freshTokenProvider != null) {
                    val newToken = freshTokenProvider() ?: throw e
                    // 新しいトークンを共有 TokenRef に反映して、以後の全取得で使う
                    tokenRef.token = newToken
                    return apiClient.getBytes(url, tokenRef.headers())
                }
                if (isRetryable5xx(e) && attempts <= SEGMENT_5XX_RETRY_COUNT) {
                    lastError = e
                    delay(SEGMENT_5XX_RETRY_DELAY_MS)
                    continue
                }
                throw e
            }
        }
        throw lastError ?: IOException("セグメント取得に失敗しました")
    }

    /** HTTP 401 (トークン失効) かどうか。 */
    private fun isAuth401(e: Exception): Boolean = (e.message ?: "").contains("HTTP 401")

    /** HTTP 5xx (一時的なサーバー障害) かどうか。 */
    private fun isRetryable5xx(e: Exception): Boolean {
        val status = (e.message ?: "").substringAfter("HTTP ", "")
            .substringBefore(":").trim().toIntOrNull()
        return status != null && status in RETRYABLE_5XX
    }

    /** セグメントを逐次取得し、ID3v2 タグを除去して書き出す。進捗はセグメントごとに通知する。 */
    private suspend fun writeSegments(
        out: OutputStream,
        segments: List<String>,
        tokenRef: TokenRef,
        onProgress: (Float) -> Unit,
        freshTokenProvider: (suspend () -> String?)?,
    ) {
        segments.forEachIndexed { i, segmentUrl ->
            val bytes = stripId3(fetchSegmentBytes(segmentUrl, tokenRef, freshTokenProvider))
            out.write(bytes)
            onProgress((i + 1f) / segments.size)
        }
    }

    /**
     * HTTP 401 (トークン失効) をユーザー向けの明確なメッセージに置き換える。
     * それ以外はそのまま再スローする。
     */
    private fun mapAuthError(e: Exception): Exception {
        if ((e.message ?: "").contains("HTTP 401")) {
            return IOException("認証の有効期限が切れました。再度お試しください", e)
        }
        return e
    }
}
