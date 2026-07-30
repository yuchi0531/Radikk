/// radiko Androidアプリのバージョンマッピング
/// rajikoの modules/static.js の APP_VERSION_MAP から抽出
class AppVersionMap {
  /// 利用可能なアプリバージョンリスト（ランダム選択用）
  static const List<String> versions = [
    '8.4.5', '8.4.4', '8.4.3', '8.4.2', '8.4.1',
    '8.3.12', '8.3.11', '8.3.10', '8.3.9', '8.3.8',
    '8.3.7', '8.3.6', '8.3.5', '8.3.4', '8.3.3',
    '8.3.2', '8.3.1', '8.3.0',
    '8.2.5', '8.2.4', '8.2.3', '8.2.2', '8.2.1', '8.2.0',
    '8.1.0', '8.0.7', '8.0.6', '8.0.5', '8.0.4', '8.0.3',
  ];

  /// バージョン → アプリタイプのマッピング
  static const Map<String, String> versionToAppType = {
    '8.4.5': 'aSmartPhone8',
    '8.4.4': 'aSmartPhone8',
    '8.4.3': 'aSmartPhone8',
    '8.4.2': 'aSmartPhone8',
    '8.4.1': 'aSmartPhone8',
    '8.3.12': 'aSmartPhone8',
    '8.3.11': 'aSmartPhone8',
    '8.3.10': 'aSmartPhone8',
    '8.3.9': 'aSmartPhone8',
    '8.3.8': 'aSmartPhone8',
    '8.3.7': 'aSmartPhone8',
    '8.3.6': 'aSmartPhone8',
    '8.3.5': 'aSmartPhone8',
    '8.3.4': 'aSmartPhone8',
    '8.3.3': 'aSmartPhone8',
    '8.3.2': 'aSmartPhone8',
    '8.3.1': 'aSmartPhone8',
    '8.3.0': 'aSmartPhone8',
    '8.2.5': 'aSmartPhone8',
    '8.2.4': 'aSmartPhone8',
    '8.2.3': 'aSmartPhone8',
    '8.2.2': 'aSmartPhone8',
    '8.2.1': 'aSmartPhone8',
    '8.2.0': 'aSmartPhone8',
    '8.1.0': 'aSmartPhone8',
    '8.0.7': 'aSmartPhone8',
    '8.0.6': 'aSmartPhone8',
    '8.0.5': 'aSmartPhone8',
    '8.0.4': 'aSmartPhone8',
    '8.0.3': 'aSmartPhone8',
  };
}
