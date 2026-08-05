import 'package:flutter/foundation.dart';

/// radiko の時刻を扱う共通ユーティリティ
///
/// radiko の ft/to は日本標準時 (JST, UTC+9) の壁時計時刻
/// "YYYYMMDDHHMMSS" 形式で表される。
/// デバイスのタイムゾーンに依存せず正しく扱うため、
/// パース結果は UTC の絶対時刻として保持し、表示時は toLocal() で変換する。

/// JSTとUTCのオフセット
const jstOffset = Duration(hours: 9);

/// radiko の "YYYYMMDDHHMMSS" (JST) を UTC の DateTime に変換する
/// 例: "20260730050000" → JST 2026-07-30 05:00:00 → UTC 2026-07-29 20:00:00
///
/// ISO 8601 形式 (DateTime.tryParse が解釈可能) の場合も受け付ける。
/// パース失敗時は現在時刻 (UTC) を返す
DateTime parseRadikoDateTime(dynamic value) {
  final str = value?.toString() ?? '';
  // ISO 8601 等、tryParse が解釈できる形式（テスト・汎用入力用）
  if (str.length != 14) {
    final parsed = DateTime.tryParse(str);
    if (parsed != null) {
      return parsed.toUtc();
    }
    return DateTime.now().toUtc();
  }
  try {
    final year = int.parse(str.substring(0, 4));
    final month = int.parse(str.substring(4, 6));
    final day = int.parse(str.substring(6, 8));
    final hour = int.parse(str.substring(8, 10));
    final minute = int.parse(str.substring(10, 12));
    final second = int.parse(str.substring(12, 14));
    // JSTの壁時計時刻 → UTC絶対時刻
    // hour - 9 は負になる場合も DateTime.utc が日付を繰り下げて正規化する
    return DateTime.utc(year, month, day, hour - 9, minute, second);
  } catch (e) {
    debugPrint('[RadikoTime] DateTime parse failed: $str - $e');
    return DateTime.now().toUtc();
  }
}

/// UTC の DateTime を radiko の "YYYYMMDDHHMMSS" (JST) 文字列に変換する
/// 例: UTC 2026-07-29 20:00:00 → "20260730050000"
String formatRadikoDateTime(DateTime dt) {
  final jst = dt.toUtc().add(jstOffset);
  return '${jst.year}'
      '${jst.month.toString().padLeft(2, '0')}'
      '${jst.day.toString().padLeft(2, '0')}'
      '${jst.hour.toString().padLeft(2, '0')}'
      '${jst.minute.toString().padLeft(2, '0')}'
      '${jst.second.toString().padLeft(2, '0')}';
}
