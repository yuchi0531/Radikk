package com.radikk.app.data.api

import android.content.Context
import java.io.IOException

/**
 * fullKey の読み込み。
 *
 * fullKey は 167KB の base64 文字列 (JPEG イメージ) で、
 * Kotlin 文字列定数の 64KB 制限を超えるため assets に配置している。
 * 読み込み結果はプロセス内でキャッシュする。
 */
class FullKeyProvider(private val context: Context) {

    @Volatile
    private var cached: String? = null

    /**
     * fullKey (base64) を返す。
     * @throws IOException assets 読み込み失敗時
     */
    fun get(): String {
        cached?.let { return it }
        val key = context.assets.open("fullkey.b64")
            .bufferedReader()
            .use { it.readText() }
            .replace(Regex("\\s+"), "")
        // base64 パディングを補正
        val padded = key + "=".repeat((4 - key.length % 4) % 4)
        cached = padded
        return padded
    }
}
