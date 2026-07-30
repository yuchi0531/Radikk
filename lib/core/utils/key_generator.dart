import 'dart:convert';
import '../constants/app_keys.dart';

/// partial key 生成（rajikoの認証方式）
class KeyGenerator {
  /// Dartのbase64Decodeは厳密に4の倍数を要求するため、
  /// JavaScriptのatob()互換になるようパディングを補完する
  static String _normalizeBase64(String base64) {
    final remainder = base64.length % 4;
    if (remainder == 0) return base64;
    return base64.padRight(base64.length + (4 - remainder), '=');
  }

  /// auth1レスポンスのoffsetとlengthからpartial keyを生成
  /// partial_key = base64_encode( full_key_decoded[offset : offset+length] )
  static String generatePartialKey(int offset, int length) {
    if (offset < 0 || length <= 0) {
      throw ArgumentError(
          'Invalid offset or length: offset=$offset, length=$length');
    }
    final fullKeyBytes = base64Decode(_normalizeBase64(AppKeys.fullKey));
    if (offset + length > fullKeyBytes.length) {
      // オーバーフロー時は切り詰め
      length = fullKeyBytes.length - offset;
    }
    final partial = fullKeyBytes.sublist(offset, offset + length);
    return base64Encode(partial);
  }
}
