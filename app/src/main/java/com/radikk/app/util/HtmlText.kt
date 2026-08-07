package com.radikk.app.util

import android.os.Build
import android.text.Html

/** HTML をプレーンテキストに変換する (タグ除去 + 実体参照解決)。 */
fun htmlToPlainText(html: String): String {
    val processed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
    } else {
        @Suppress("DEPRECATION")
        Html.fromHtml(html).toString()
    }
    return processed.trim()
}
