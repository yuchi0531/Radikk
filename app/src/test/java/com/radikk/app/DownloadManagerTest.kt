package com.radikk.app

import com.radikk.app.data.download.DownloadManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DownloadManager の単体テスト (純 JVM、Context 不要)。
 *
 * 対象は ID3v2 タグ除去 [DownloadManager.stripId3]。
 * radiko の .aac セグメントは「ID3v2 タグ + ADTS AAC」の構成のため、
 * 連結前にタグ部分を除去して純粋な ADTS AAC だけを書き出す必要がある。
 */
class DownloadManagerTest {

    /**
     * 「ID3v2 タグ本体 + その後の ADTS データ」を持つデータを組み立てる。
     * ヘッダー: "ID3" + バージョン 2B + フラグ 1B + サイズ 4B (syncsafe)。
     * ID3 サイズフィールドはヘッダー (10B) を除いたタグ本体のサイズを表す。
     * @param tagBody ID3 タグ本体 (ヘッダーに続く部分)
     * @param after   タグの後に続く ADTS AAC データ
     */
    private fun id3Prefixed(tagBody: ByteArray, after: ByteArray): ByteArray {
        val header = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(),
            0x04, 0x00, 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), // syncsafe size (下で書き換え)
        )
        // syncsafe で tagBody サイズをエンコード (テストは小さい値のみ)
        header[6] = ((tagBody.size ushr 21) and 0x7F).toByte()
        header[7] = ((tagBody.size ushr 14) and 0x7F).toByte()
        header[8] = ((tagBody.size ushr 7) and 0x7F).toByte()
        header[9] = (tagBody.size and 0x7F).toByte()
        return header + tagBody + after
    }

    @Test
    fun `ID3v2 タグ付きデータはタグが除去される`() {
        val tagBody = byteArrayOf(0x54, 0x49, 0x54, 0x32, 0x00, 0x00, 0x00, 0x0A) // "TIT2" フレーム相当
        val adts = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50, 0x80.toByte(), 0x01, 0x23) // ADTS AAC
        val data = id3Prefixed(tagBody, adts)
        val stripped = DownloadManager.stripId3(data)
        assertArrayEquals(adts, stripped)
    }

    @Test
    fun `ID3v2 タグの無いデータはそのまま返る`() {
        val data = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50, 0x80.toByte(), 0x01, 0x23)
        assertArrayEquals(data, DownloadManager.stripId3(data))
    }

    @Test
    fun `10バイト未満のデータはそのまま返る`() {
        val data = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 0x00, 0x00)
        assertEquals(5, data.size)
        assertArrayEquals(data, DownloadManager.stripId3(data))
    }

    @Test
    fun `ID3v2 タグのサイズがデータ全体を超える場合は空配列を返す`() {
        // ヘッダー10B + サイズ (0x7F を 4 バイト) = 巨大 → データより大きい →
        // タグ本体が欠落した不完全なセグメント。元データを返すと ID3 が残ったままになり
        // ADTS フレーミングが壊れるため、空配列を返してセグメントを破棄する。
        val header = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(),
            0x04, 0x00, 0x00.toByte(),
            0x7F, 0x7F, 0x7F, 0x7F.toByte(),
        )
        val data = header + byteArrayOf(0xFF.toByte(), 0xF1.toByte())
        assertEquals(0, DownloadManager.stripId3(data).size)
    }
}
