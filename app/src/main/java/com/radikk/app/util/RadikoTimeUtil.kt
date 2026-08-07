package com.radikk.app.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * radiko の時刻処理ユーティリティ。
 *
 * radiko の時刻はすべて JST (Asia/Tokyo) 基準。
 * - 内部は UTC で保持し、表示時に JST へ変換する
 * - API の ft/to は JST 14桁 (YYYYMMDDHHMMSS)
 * - 番組表の日付は JST 5:00 起点 (深夜 0:00-4:59 は前日分)
 */
object RadikoTimeUtil {

    val JST: ZoneId = ZoneId.of("Asia/Tokyo")
    val UTC: ZoneId = ZoneOffset.UTC

    /** JST の 5:00 起点 */
    private const val DAY_START_HOUR = 5

    /** JST 14桁フォーマッタ (YYYYMMDDHHMMSS) */
    private val apiFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.US).withZone(JST)

    /** 表示用フォーマッタ */
    val timeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm", Locale.JAPAN)

    val dateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("M/d(EEE)", Locale.JAPAN)

    /**
     * JST 14桁 (YYYYMMDDHHMMSS) を UTC の Instant に変換する。
     * @throws DateTimeParseException 不正な文字列の場合
     */
    fun parseJst14ToInstant(jst14: String): Instant {
        val dt = LocalDateTime.parse(jst14, apiFormatter)
        return dt.atZone(JST).toInstant()
    }

    /** Instant を JST 14桁に変換する。 */
    fun formatJst14(instant: Instant): String = apiFormatter.format(instant)

    /** Instant を JST の HH:mm に変換する。 */
    fun formatTime(instant: Instant): String = timeFormatter.format(instant.atZone(JST))

    /** Instant を JST の M/d(EEE) に変換する。 */
    fun formatDate(instant: Instant): String = dateFormatter.format(instant.atZone(JST))

    /**
     * JST 5:00 起点で、指定 Instant が属する「放送日」の開始時刻 (JST 5:00) を返す。
     * 例: 2026-08-07 03:00 JST → 2026-08-06 05:00 JST (前日分)
     */
    fun dayStartOf(instant: Instant): Instant {
        val zdt = instant.atZone(JST)
        var date = zdt.toLocalDate()
        if (zdt.hour < DAY_START_HOUR) {
            date = date.minusDays(1)
        }
        return date.atTime(DAY_START_HOUR, 0).atZone(JST).toInstant()
    }

    /** JST 5:00 起点の放送日の終了時刻 (翌日 5:00) を返す。 */
    fun dayEndOf(instant: Instant): Instant = dayStartOf(instant).plusSeconds(24 * 3600)

    /** 今日 (JST) の 5:00 起点の放送日開始。 */
    fun todayDayStart(): Instant = dayStartOf(Instant.now())

    /** 放送日開始 Instant を JST の YYYYMMDD に変換 (番組表 API 用)。 */
    fun formatApiDate(instant: Instant): String =
        DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US).withZone(JST).format(instant)

    /** 放送日 (5:00起点) の JST 日付文字列 YYYYMMDD。 */
    fun apiDateFor(instant: Instant): String = formatApiDate(dayStartOf(instant))

    /** JST での現在時刻 Instant (検証・テスト用)。 */
    fun now(): Instant = Instant.now()

    /** 現在放送中の番組かを判定する。 */
    fun isOnAir(ft: Instant, to: Instant, now: Instant = Instant.now()): Boolean =
        now >= ft && now < to
}
