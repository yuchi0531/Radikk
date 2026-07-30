import 'package:dio/dio.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../../core/constants/api_endpoints.dart';
import '../../core/constants/device_config.dart';
import '../../core/models/auth_token.dart';
import '../../core/network/dio_client.dart';
import '../../core/utils/device_info_generator.dart';
import '../../core/utils/gps_generator.dart';
import '../../core/utils/key_generator.dart';

/// radiko 認証サービス（androidアプリ認証フロー）
class AuthService {
  final _dio = RadikoDioClient();
  static const _tokenKey = 'radiko_auth_token';

  /// 端末情報
  String? _userId;
  String? _device;

  void _ensureDeviceInfo() {
    _userId ??= DeviceInfoGenerator.generateUserId();
    _device ??= DeviceInfoGenerator.generateDevice();
  }

  /// auth1: 認証トークンとキーオフセットを取得
  /// POST https://api.radiko.jp/v2/api/auth1
  Future<AuthToken> auth1() async {
    _ensureDeviceInfo();
    final appVersion = DeviceInfoGenerator.generateAppVersion();

    final response = await _dio.authClient.get(
      ApiEndpoints.auth1,
      options: Options(
        headers: {
          'X-Radiko-App': DeviceConfig.appType,
          'X-Radiko-App-Version': appVersion,
          'X-Radiko-Device': _device!,
          'X-Radiko-User': _userId!,
        },
      ),
    );

    final token = response.headers.value('X-Radiko-AuthToken');
    final keyOffset =
        int.tryParse(response.headers.value('X-Radiko-KeyOffset') ?? '0') ?? 0;
    final keyLength =
        int.tryParse(response.headers.value('X-Radiko-KeyLength') ?? '0') ?? 0;

    if (token == null || token.isEmpty) {
      throw AuthException('auth1失败: トークンが取得できませんでした');
    }

    return AuthToken(
      token: token,
      keyOffset: keyOffset,
      keyLength: keyLength,
      createdAt: DateTime.now(),
    );
  }

  /// auth2: 認証完了、エリア情報を取得
  /// POST https://api.radiko.jp/v2/api/auth2
  Future<AuthToken> auth2(AuthToken token, String areaId) async {
    _ensureDeviceInfo();

    final partialKey = KeyGenerator.generatePartialKey(
      token.keyOffset,
      token.keyLength,
    );

    final location = GpsGenerator.generateForArea(areaId);

    try {
      final response = await _dio.authClient.get(
        ApiEndpoints.auth2,
        options: Options(
          headers: {
            'X-Radiko-App': DeviceConfig.appType,
            'X-Radiko-App-Version': DeviceInfoGenerator.generateAppVersion(),
            'X-Radiko-Device': _device!,
            'X-Radiko-User': _userId!,
            'X-Radiko-AuthToken': token.token,
            'X-Radiko-Partialkey': partialKey,
            'X-Radiko-Location': location,
          },
        ),
      );

      // ステータスコードで判定（rajikoの仕様に準拠）
      if (response.statusCode == 401 || response.statusCode == 403) {
        throw AuthException('auth2失败: 認証エラー (status: ${response.statusCode})');
      }

      final body = response.data.toString().trim();

      // レスポンス: "JP13,東京都,tokyo Japan"
      if (body == 'OUT') {
        throw AuthException('auth2失败: 地域外と判定されました (OUT)');
      }

      final parts = body.split(',');
      final resolvedAreaId = parts.isNotEmpty ? parts[0] : areaId;
      final resolvedAreaName = parts.length > 1 ? parts[1] : areaId;

      final completedToken = AuthToken(
        token: token.token,
        keyOffset: token.keyOffset,
        keyLength: token.keyLength,
        areaId: resolvedAreaId,
        areaName: resolvedAreaName,
        createdAt: token.createdAt,
      );

      // キャッシュに保存
      await _saveToken(completedToken);

      return completedToken;
    } catch (e) {
      if (e is AuthException) rethrow;
      throw AuthException('auth2失败: $e');
    }
  }

  /// 完全な認証フロー（auth1 → auth2）
  Future<AuthToken> authenticate(String areaId) async {
    // キャッシュされた有効なトークンを確認
    final cached = await _loadToken();
    if (cached != null && !cached.isExpired) {
      // エリアが一致する場合は再利用
      if (cached.areaId == areaId) {
        return cached;
      }
      // 異なるエリアの場合は再認証（auth_checkで検証してもトークンは
      // 元のエリアに紐付いているため、新しいエリアでの再認証が必要）
    }

    final token = await auth1();
    return auth2(token, areaId);
  }

  /// トークンの有効性を確認（auth_check API）
  /// エンドポイント: https://radiko.jp/v2/api/auth_check
  Future<bool> verifyToken(String token) async {
    try {
      final response = await _dio.apiClient.get(
        ApiEndpoints.authCheck,
        options: Options(
          headers: {
            'X-Radiko-AuthToken': token,
          },
        ),
      );
      return response.data.toString().trim() == 'OK';
    } catch (e) {
      return false;
    }
  }

  /// トークンキャッシュから読み込み
  Future<AuthToken?> _loadToken() async {
    final prefs = await SharedPreferences.getInstance();
    final json = prefs.getString(_tokenKey);
    if (json == null) return null;

    try {
      // simple JSON parse without depending on fromJson
      final parts = json.split('|');
      if (parts.length < 5) return null;
      final token = AuthToken(
        token: parts[0],
        keyOffset: int.tryParse(parts[1]) ?? 0,
        keyLength: int.tryParse(parts[2]) ?? 0,
        areaId: parts[3].isEmpty ? null : parts[3],
        areaName: parts[4].isEmpty ? null : parts[4],
        createdAt: DateTime.tryParse(parts[5]) ?? DateTime.now(),
      );
      return token;
    } catch (_) {
      return null;
    }
  }

  /// トークンをキャッシュに保存
  Future<void> _saveToken(AuthToken token) async {
    final prefs = await SharedPreferences.getInstance();
    final json = [
      token.token,
      token.keyOffset.toString(),
      token.keyLength.toString(),
      token.areaId ?? '',
      token.areaName ?? '',
      token.createdAt.toIso8601String(),
    ].join('|');
    await prefs.setString(_tokenKey, json);
  }

  /// トークンキャッシュをクリア
  Future<void> clearToken() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_tokenKey);
  }
}

/// 認証例外
class AuthException implements Exception {
  final String message;
  AuthException(this.message);

  @override
  String toString() => 'AuthException: $message';
}
