package com.radikk.app.data.auth

import android.util.Log
import com.radikk.app.data.api.FullKeyProvider
import com.radikk.app.data.api.RadikoApi
import com.radikk.app.data.api.RadikoApiClient
import com.radikk.app.data.datastore.SettingsRepository
import com.radikk.app.data.datastore.StoredAuth
import com.radikk.app.data.model.AuthSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Instant
import java.util.Base64

/**
 * 認証フロー (auth1 → auth2) を管理するリポジトリ。
 *
 * 検証済み仕様:
 * 1. auth1: GET /v2/api/auth1
 *    - ヘッダー: X-Radiko-App / X-Radiko-App-Version / X-Radiko-Device / X-Radiko-User
 *    - レスポンスヘッダー: X-Radiko-AuthToken / X-Radiko-KeyOffset / X-Radiko-KeyLength
 * 2. auth2: GET /v2/api/auth2
 *    - 上記 + X-Radiko-AuthToken + X-Radiko-Partialkey + X-Radiko-Location
 *    - Partialkey = fullKey を base64 デコード → [offset, offset+length) を切り出し → 再 base64
 *    - レスポンスボディ: "JP13,東京都,tokyo Japan" (OUT なら地域外)
 *
 * トークンは約90分で期限切れ。有効期限内かつ areaId 一致ならキャッシュ再利用。
 * 認証はシングルフライト化 (同時呼び出しは1つに集約)。
 */
class AuthRepository(
    private val settings: SettingsRepository,
    private val apiClient: RadikoApiClient,
    private val fullKeyProvider: FullKeyProvider,
) {
    companion object {
        private const val TAG = "AuthRepository"

        /** トークン有効期間 (ミリ秒)。約90分 - マージン10分 */
        private const val TOKEN_TTL_MS = 80L * 60 * 1000

        /**
         * fullKey (base64) から partialkey を生成する。
         * base64 デコード → [keyOffset, keyOffset+keyLength) を切り出し → 再 base64。
         */
        fun buildPartialKey(fullKeyBase64: String, keyOffset: Int, keyLength: Int): String {
            val decoded = Base64.getDecoder().decode(fullKeyBase64)
            require(keyOffset >= 0 && keyOffset + keyLength <= decoded.size) {
                "KeyOffset/KeyLength が fullKey のサイズを超えています"
            }
            val slice = decoded.copyOfRange(keyOffset, keyOffset + keyLength)
            return Base64.getEncoder().encodeToString(slice)
        }

        /** 認証エラー種別 */
        sealed class AuthError(message: String) : Exception(message) {
            /** 地域外 (OUT) */
            class AreaOut : AuthError("このエリアでは radiko を利用できません (地域外)")
            /** 認証失敗 (401 等) */
            class AuthFailed(message: String) : AuthError(message)
            /** ネットワークエラー */
            class Network(message: String) : AuthError(message)
        }
    }

    private val authMutex = Mutex()
    private var cachedSession: AuthSession? = null

    /**
     * 現在のエリアの有効な認証セッションを返す。
     * キャッシュが有効なら再利用、そうでなければ認証を実行する。
     * エリア変更時は必ず再認証する。
     *
     * 認証は Mutex でシングルフライト化されている (同時呼び出しは1つに集約)。
     */
    suspend fun getSession(areaId: String): AuthSession = authMutex.withLock {
        // キャッシュチェック
        cachedSession?.let { session ->
            if (session.areaId == areaId && session.isValid) {
                return session
            }
        }

        // DataStore に永続化済みのセッションを復元
        loadStoredSession(areaId)?.let { session ->
            cachedSession = session
            return session
        }

        // 認証実行
        val session = authenticate(areaId)
        cachedSession = session
        settings.saveAuth(session.toStored())
        session
    }

    /** エリアを変えて再認証を強制する (エリア選択変更時)。 */
    suspend fun refreshSession(areaId: String): AuthSession = authMutex.withLock {
        cachedSession = null
        val session = authenticate(areaId)
        cachedSession = session
        settings.saveAuth(session.toStored())
        session
    }

    /** 永続化済みセッションを復元する。エリア一致 + 有効期限内のみ。 */
    private suspend fun loadStoredSession(areaId: String): AuthSession? {
        val stored = settings.currentAuth()
        val token = stored.token ?: return null
        val storedArea = stored.areaId ?: return null
        val expires = stored.expiresAtEpochMillis ?: return null
        if (storedArea != areaId) return null
        val expiry = Instant.ofEpochMilli(expires)
        if (!expiry.isAfter(Instant.now())) return null
        return AuthSession(
            token = token,
            areaId = storedArea,
            areaName = stored.areaName ?: "",
            device = stored.device ?: RadikoApi.DEFAULT_DEVICE,
            userId = stored.userId ?: "",
            expiresAt = expiry,
        )
    }

    /**
     * auth1 → auth2 を実行してセッションを取得する。
     */
    private suspend fun authenticate(areaId: String): AuthSession {
        val device = settings.currentAuth().device ?: RadikoApi.DEFAULT_DEVICE
        val userId = settings.currentAuth().userId ?: RadikoApi.userId("radikk-${device}-v1")

        return try {
            // --- auth1 ---
            val auth1Headers = mapOf(
                "X-Radiko-App" to RadikoApi.APP,
                "X-Radiko-App-Version" to RadikoApi.APP_VERSION,
                "X-Radiko-Device" to device,
                "X-Radiko-User" to userId,
            )
            val auth1Resp = apiClient.getAuth(RadikoApi.AUTH1_URL, auth1Headers)
            val token: String
            val keyOffset: Int
            val keyLength: Int
            try {
                token = auth1Resp.header("X-Radiko-AuthToken")
                    ?: throw AuthError.AuthFailed("auth1 レスポンスにトークンがありません")
                keyOffset = auth1Resp.header("X-Radiko-KeyOffset")
                    ?.toIntOrNull()
                    ?: throw AuthError.AuthFailed("auth1 レスポンスの KeyOffset が不正です")
                keyLength = auth1Resp.header("X-Radiko-KeyLength")
                    ?.toIntOrNull()
                    ?: throw AuthError.AuthFailed("auth1 レスポンスの KeyLength が不正です")
            } finally {
                auth1Resp.close()
            }

            // --- partialkey 生成 ---
            val fullKey = fullKeyProvider.get()
            val partialKey = buildPartialKey(fullKey, keyOffset, keyLength)

            // --- auth2 ---
            val coords = RadikoApi.AREA_COORDS[areaId] ?: (35.689488 to 139.691706)
            val location = "${coords.first},${coords.second},gps"
            val auth2Headers = auth1Headers + mapOf(
                "X-Radiko-AuthToken" to token,
                "X-Radiko-Partialkey" to partialKey,
                "X-Radiko-Location" to location,
            )
            apiClient.getAuth(RadikoApi.AUTH2_URL, auth2Headers).use { auth2Resp ->
                if (!auth2Resp.isSuccessful) {
                    throw AuthError.AuthFailed("auth2 失敗 (HTTP ${auth2Resp.code})")
                }
                val body = auth2Resp.body?.string()?.trim().orEmpty()
                Log.d(TAG, "auth2 body: $body")
                if (body.startsWith("OUT")) {
                    throw AuthError.AreaOut()
                }
                // "JP13,東京都,tokyo Japan" 形式
                val parts = body.split(",")
                if (parts.size < 2) {
                    throw AuthError.AuthFailed("auth2 レスポンス不正: $body")
                }
                val actualAreaId = parts[0].trim()
                val areaName = parts[1].trim()
                val expiry = Instant.now().plusMillis(TOKEN_TTL_MS)
                return AuthSession(
                    token = token,
                    areaId = actualAreaId,
                    areaName = areaName,
                    device = device,
                    userId = userId,
                    expiresAt = expiry,
                )
            }
        } catch (e: AuthError) {
            throw e
        } catch (e: IOException) {
            throw AuthError.Network(e.message ?: "ネットワークエラー")
        }
    }

    private fun AuthSession.toStored(): StoredAuth = StoredAuth(
        token = token,
        areaId = areaId,
        areaName = areaName,
        device = device,
        userId = userId,
        expiresAtEpochMillis = expiresAt.toEpochMilli(),
    )
}
