package com.radikk.app.data.model

import java.time.Instant

/**
 * 放送局。
 * @param areaIds この局が放送しているエリアID (JP13 等)
 */
data class Station(
    val id: String,
    val name: String,
    val asciiName: String,
    val areafree: Boolean,
    val timefree: Boolean,
    val areaIds: List<String>,
    val logoUrl: String?,
)

/**
 * 番組。
 * @param ft 放送開始 (UTC)。JST 14桁から変換済み
 * @param to 放送終了 (UTC)
 */
data class Program(
    val stationId: String,
    val ft: Instant,
    val to: Instant,
    val title: String,
    val description: String?,
    val performer: String?,
    val episodeId: String?,
    val imgUrl: String?,
) {
    /** 現在放送中かどうか */
    fun isOnAir(now: Instant = Instant.now()): Boolean = now >= ft && now < to
}

/**
 * 認証セッション。DataStore に永続化される。
 * @param expiresAt トークン有効期限 (UTC)。約90分
 */
data class AuthSession(
    val token: String,
    val areaId: String,
    val areaName: String,
    val device: String,
    val userId: String,
    val expiresAt: Instant,
) {
    /** 有効期限内かどうか */
    val isValid: Boolean
        get() = expiresAt.isAfter(Instant.now())
}
