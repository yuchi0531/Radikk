package com.radikk.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.radikk.app.data.datastore.ThemeMode

/**
 * radiko のブランドカラー (赤) を基調にした M3 テーマ。
 * Android 12+ では常に Monet (ダイナミックカラー) を使用し、設定 (ライト/ダーク/自動) と連動する。
 */

// radiko のブランドレッド (Android 12 未満のフォールバック用)
val RadikoRed = Color(0xFFE60000)

private val LightColors = lightColorScheme(
    primary = RadikoRed,
)

private val DarkColors = darkColorScheme(
    primary = RadikoRed,
)

@Composable
fun RadikkTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // Android 12+ は常に Monet (壁紙由来のダイナミックカラー) を使用する
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        // Android 12 未満は radiko ブランドレッドのフォールバック
        if (darkTheme) DarkColors else LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
