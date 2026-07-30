/// 端末情報・デバイス設定
class DeviceConfig {
  /// radiko Androidアプリの固定User-Agent
  static const String userAgent =
      'Mozilla/5.0 (Linux; Android 10; Pixel 4 XL) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/80.0.3987.87 Mobile Safari/537.36';

  /// アプリタイプ（最新）
  static const String appType = 'aSmartPhone8';

  /// 接続タイプ
  static const String connection = 'wifi';

  /// トークン有効期限（秒）- 90分
  static const int tokenExpirySeconds = 5400;

  /// トークン再取得までの猶予（秒）- 70分
  static const int tokenRefreshSeconds = 4200;
}
