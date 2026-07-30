import 'dart:math';

/// ランダムなAndroid端末情報を生成する
class DeviceInfoGenerator {
  static final _random = Random();

  // Android SDKバージョンとバージョン番号
  static const _sdkVersions = [24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34];

  // 日本のAndroid端末モデルリスト
  static const _models = [
    'GTT9Q',    // Samsung Galaxy
    '901SH',    // Sharp Aquos
    '702SO',    // Sony Xperia
    'SC-53C',   // Samsung Galaxy
    'SO-53B',   // Sony Xperia
    'A202SO',   // Sony Xperia
    'SH-53C',   // Sharp Aquos
    'F-52B',    // Fujitsu Arrows
    'KYG01',    // Kyocera
    'Pixel 5',  // Google Pixel
    'Pixel 4a', // Google Pixel
    'Pixel 6',  // Google Pixel
  ];

  /// ランダムな32文字の16進数 user_id を生成
  static String generateUserId() {
    final bytes = List<int>.generate(16, (_) => _random.nextInt(256));
    return bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
  }

  /// ランダムなSDKバージョン番号（例: "34"）
  static String generateSdkVersion() {
    return _sdkVersions[_random.nextInt(_sdkVersions.length)].toString();
  }

  /// ランダムな端末モデル名
  static String generateModel() {
    return _models[_random.nextInt(_models.length)];
  }

  /// デバイス文字列（例: "34.GTT9Q"）
  static String generateDevice() {
    return '${generateSdkVersion()}.${generateModel()}';
  }
}
