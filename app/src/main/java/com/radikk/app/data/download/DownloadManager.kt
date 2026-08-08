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
         * セグメント先頭の ID3v2 タグを除去して純粋な ADTS AAC だけを返す。
         * radiko の .aac セグメントは「ID3v2 タグ + ADTS」の構成。
         * ID3v2 ヘッダーは "ID3" + バージョン2B + フラグ1B + サイズ4B (syncsafe)。
         * ヘッダー10B + サイズ分をスキップする。
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
            }
            return data
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
        val headers = mapOf("X-Radiko-AuthToken" to token)
        val durationMs = (to.toEpochMilli() - ft.toEpochMilli()).coerceAtLeast(0L)

        // 1-4. ウィンドウループで番組全体のセグメント URL を収集
        val segments = try {
            collectAllSegmentUrls(stationId, ft, to, durationMs, headers, tokenProvider)
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
                    writeSegments(out, segments, headers, onProgress, tokenProvider)
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
                    writeSegments(out, segments, headers, onProgress, tokenProvider)
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
     */
    private suspend fun collectAllSegmentUrls(
        stationId: String,
        ft: Instant,
        to: Instant,
        durationMs: Long,
        headers: Map<String, String>,
        freshTokenProvider: (suspend () -> String?)?,
    ): List<String> {
        val allSegments = LinkedHashSet<String>()
        var windowStartMs = 0L
        while (true) {
            val previousCount = allSegments.size
            val (medialistUrl, body) = fetchWindowBody(stationId, ft, to, windowStartMs, headers, freshTokenProvider)
            allSegments.addAll(resolver.extractSegmentUrls(body, medialistUrl))

            // ウィンドウを進めても新しいセグメントが得られない場合は
            // これ以上進んでも無限に空ループするだけなので中断する。
            if (allSegments.size == previousCount) {
                Log.w(TAG, "ウィンドウ $windowStartMs で新しいセグメントが得られませんでした。収集を中断します (${allSegments.size} 件)")
                break
            }

            windowStartMs += WINDOW_MS
            // 次のウィンドウ開始が番組終了以上になったら終了
            if (windowStartMs >= durationMs) break
        }
        return allSegments.toList()
    }

    /**
     * 1 ウィンドウ分の medialist を取得する。
     * 401 (トークン失効) の場合は [freshTokenProvider] で新トークンを取得して
     * そのウィンドウだけ 1 回再試行する。再試行が無い / 失敗した場合は元の例外を投げる。
     * @return medialist URL と body のペア (URL 相対のセグメント解決に medialist URL が必要なため)
     */
    private suspend fun fetchWindowBody(
        stationId: String,
        ft: Instant,
        to: Instant,
        seekOffsetMs: Long,
        headers: Map<String, String>,
        freshTokenProvider: (suspend () -> String?)?,
    ): Pair<String, String> {
        val token = headers["X-Radiko-AuthToken"]!!
        try {
            val url = resolver.resolveTimefreeMedialistUrl(stationId, token, ft, to, seekOffsetMs = seekOffsetMs)
            return url to apiClient.getString(url, headers)
        } catch (e: Exception) {
            if (isAuth401(e) && freshTokenProvider != null) {
                val newToken = freshTokenProvider() ?: throw e
                val newHeaders = mapOf("X-Radiko-AuthToken" to newToken)
                val url = resolver.resolveTimefreeMedialistUrl(stationId, newToken, ft, to, seekOffsetMs = seekOffsetMs)
                return url to apiClient.getString(url, newHeaders)
            }
            throw e
        }
    }

    /**
     * 1 セグメント分のバイト列を取得する。
     * 401 (トークン失効) の場合は [freshTokenProvider] で新トークンを取得して
     * そのセグメントだけ 1 回再試行する。再試行が無い / 失敗した場合は元の例外を投げる。
     */
    private suspend fun fetchSegmentBytes(
        url: String,
        headers: Map<String, String>,
        freshTokenProvider: (suspend () -> String?)?,
    ): ByteArray {
        try {
            return apiClient.getBytes(url, headers)
        } catch (e: Exception) {
            if (isAuth401(e) && freshTokenProvider != null) {
                val newToken = freshTokenProvider() ?: throw e
                return apiClient.getBytes(url, mapOf("X-Radiko-AuthToken" to newToken))
            }
            throw e
        }
    }

    /** HTTP 401 (トークン失効) かどうか。 */
    private fun isAuth401(e: Exception): Boolean = (e.message ?: "").contains("HTTP 401")

    /** セグメントを逐次取得し、ID3v2 タグを除去して書き出す。進捗はセグメントごとに通知する。 */
    private suspend fun writeSegments(
        out: OutputStream,
        segments: List<String>,
        headers: Map<String, String>,
        onProgress: (Float) -> Unit,
        freshTokenProvider: (suspend () -> String?)?,
    ) {
        segments.forEachIndexed { i, segmentUrl ->
            val bytes = stripId3(fetchSegmentBytes(segmentUrl, headers, freshTokenProvider))
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
