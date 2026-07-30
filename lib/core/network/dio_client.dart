import 'package:dio/dio.dart';
import '../constants/api_endpoints.dart';
import '../constants/device_config.dart';

/// radiko API 用 Dio HTTP クライアント
class RadikoDioClient {
  static RadikoDioClient? _instance;
  late final Dio _authDio;
  late final Dio _apiDio;

  RadikoDioClient._() {
    _authDio = _createDio(ApiEndpoints.baseUrl);
    _apiDio = _createDio(ApiEndpoints.oldBaseUrl);
  }

  factory RadikoDioClient() {
    _instance ??= RadikoDioClient._();
    return _instance!;
  }

  static Dio _createDio(String baseUrl) {
    final dio = Dio(BaseOptions(
      baseUrl: baseUrl,
      connectTimeout: const Duration(seconds: 15),
      receiveTimeout: const Duration(seconds: 30),
      headers: {
        'User-Agent': DeviceConfig.userAgent,
        'Accept': '*/*',
      },
    ));
    return dio;
  }

  /// 認証用クライアント（api.radiko.jp）
  Dio get authClient => _authDio;

  /// 一般API用クライアント（radiko.jp）
  Dio get apiClient => _apiDio;
}
