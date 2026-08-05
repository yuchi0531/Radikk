import 'dart:math';
import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/models/auth_token.dart';
import '../../core/network/radiko_api_client.dart';
import '../../core/utils/radiko_time.dart';
import '../auth/auth_provider.dart';
import '../stations/station_repository.dart';
import 'player_service.dart';

/// PlayerService シングルトン
final playerServiceProvider = Provider<PlayerService>((ref) {
  final service = PlayerService();
  ref.onDispose(service.dispose);
  return service;
});

/// プレイヤー状態
class PlayerState {
  final PlayerStatus status;
  final String? stationId;
  final String? stationName;
  final String? programTitle;
  final Duration position;
  final Duration? duration;
  final bool isTimefree;
  final String? errorMessage;
  final DateTime? timefreeStartTime;
  final DateTime? timefreeEndTime;

  const PlayerState({
    this.status = PlayerStatus.idle,
    this.stationId,
    this.stationName,
    this.programTitle,
    this.position = Duration.zero,
    this.duration,
    this.isTimefree = false,
    this.errorMessage,
    this.timefreeStartTime,
    this.timefreeEndTime,
  });

  PlayerState copyWith({
    PlayerStatus? status,
    String? stationId,
    String? stationName,
    String? programTitle,
    Duration? position,
    Duration? duration,
    bool? isTimefree,
    String? errorMessage,
    DateTime? timefreeStartTime,
    DateTime? timefreeEndTime,
    bool clearError = false,
  }) {
    return PlayerState(
      status: status ?? this.status,
      stationId: stationId ?? this.stationId,
      stationName: stationName ?? this.stationName,
      programTitle: programTitle ?? this.programTitle,
      position: position ?? this.position,
      duration: duration ?? this.duration,
      isTimefree: isTimefree ?? this.isTimefree,
      errorMessage: clearError ? null : (errorMessage ?? this.errorMessage),
      timefreeStartTime: timefreeStartTime ?? this.timefreeStartTime,
      timefreeEndTime: timefreeEndTime ?? this.timefreeEndTime,
    );
  }
}

/// プレイヤー状態を管理するNotifier
class PlayerNotifier extends StateNotifier<PlayerState> {
  final PlayerService _service;
  final RadikoApiClient _apiClient;
  final Ref _ref;
  final Random _random = Random();

  PlayerNotifier(this._service, this._apiClient, this._ref)
      : super(const PlayerState()) {
    _service.onStatusChanged = (status) {
      state = state.copyWith(status: status);
    };
    _service.onPositionChanged = (position, duration) {
      state = state.copyWith(position: position, duration: duration);
    };
    _service.onError = (message) {
      state = state.copyWith(
        status: PlayerStatus.error,
        errorMessage: message,
      );
    };
  }

  /// ライブ再生開始
  Future<void> playLive({
    required String stationId,
    required String stationName,
    String? programTitle,
  }) async {
    state = state.copyWith(
      status: PlayerStatus.loading,
      stationId: stationId,
      stationName: stationName,
      programTitle: programTitle,
      isTimefree: false,
      timefreeStartTime: null,
      timefreeEndTime: null,
      clearError: true,
    );

    try {
      // 認証状態からトークンを取得（なければ認証を実行）
      final token = await _getValidToken();

      final playlistUrl =
          await _apiClient.getPlaylistCreateUrl(stationId, timefree: false);

      // ライブストリームURL構築（検証済み仕様）
      // ?station_id={id}&l=300&type=b&lsid={lsid}
      final streamUrl = _buildLiveStreamUrl(playlistUrl, stationId);

      await _service.playLive(
        url: streamUrl,
        authToken: token.token,
        areaId: token.areaId,
        stationId: stationId,
        programTitle: programTitle,
      );
    } catch (e) {
      final message = _formatError(e);
      state = state.copyWith(
        status: PlayerStatus.error,
        errorMessage: message,
      );
    }
  }

  /// タイムフリー再生開始
  Future<void> playTimefree({
    required String stationId,
    required String stationName,
    required String programTitle,
    required DateTime startTime,
    required DateTime endTime,
  }) async {
    state = state.copyWith(
      status: PlayerStatus.loading,
      stationId: stationId,
      stationName: stationName,
      programTitle: programTitle,
      isTimefree: true,
      timefreeStartTime: startTime,
      timefreeEndTime: endTime,
      clearError: true,
    );

    try {
      // 認証状態からトークンを取得（なければ認証を実行）
      final token = await _getValidToken();

      final playlistUrl =
          await _apiClient.getPlaylistCreateUrl(stationId, timefree: true);
      final fromStr = formatRadikoDateTime(startTime);
      final toStr = formatRadikoDateTime(endTime);

      // タイムフリーストリームURL構築（検証済み仕様）
      // ?station_id={id}&ft={from}&to={to}&start_at={from}&end_at={to}&type=b&l=300&seek={from}&lsid={lsid}
      final url = _buildTimefreeStreamUrl(
        playlistUrl,
        stationId,
        fromStr,
        toStr,
      );

      await _service.playTimefree(
        url: url,
        authToken: token.token,
        areaId: token.areaId,
        stationId: stationId,
        programTitle: programTitle,
      );
    } catch (e) {
      final message = _formatError(e);
      state = state.copyWith(
        status: PlayerStatus.error,
        errorMessage: message,
      );
    }
  }

  /// 有効な認証トークンを取得する
  /// キャッシュが無効（未認証 or 期限切れ）の場合は再認証する
  Future<AuthToken> _getValidToken() async {
    final authState = _ref.read(authStateProvider);
    var token = authState.valueOrNull;
    if (token == null || token.isExpired) {
      await _ref.read(authStateProvider.notifier).authenticate();
      final authState2 = _ref.read(authStateProvider);
      token = authState2.valueOrNull;
      if (token == null) {
        throw Exception('認証に失敗しました');
      }
    }
    return token;
  }

  /// 32文字のランダム16進数 (lsid) を生成
  String _generateLsid() {
    final random = _random;
    final sb = StringBuffer();
    for (var i = 0; i < 32; i++) {
      sb.write('0123456789abcdef'[random.nextInt(16)]);
    }
    return sb.toString();
  }

  /// ライブストリームURL構築（検証済み仕様）
  /// ?station_id={id}&l=300&type=b&lsid={lsid}
  String _buildLiveStreamUrl(String playlistUrl, String stationId) {
    final separator = playlistUrl.contains('?') ? '&' : '?';
    return '$playlistUrl${separator}station_id=$stationId'
        '&l=300&type=b&lsid=${_generateLsid()}';
  }

  /// タイムフリーストリームURL構築（検証済み仕様）
  /// ?station_id={id}&ft={from}&to={to}&start_at={from}&end_at={to}&type=b&l=300&seek={from}&lsid={lsid}
  String _buildTimefreeStreamUrl(
    String playlistUrl,
    String stationId,
    String fromStr,
    String toStr,
  ) {
    final separator = playlistUrl.contains('?') ? '&' : '?';
    return '$playlistUrl${separator}station_id=$stationId'
        '&ft=$fromStr&to=$toStr'
        '&start_at=$fromStr&end_at=$toStr'
        '&type=b&l=300&seek=$fromStr'
        '&lsid=${_generateLsid()}';
  }

  /// エラーメッセージをユーザーフレンドリーに整形
  String _formatError(Object e) {
    if (e is DioException) {
      switch (e.type) {
        case DioExceptionType.connectionTimeout:
        case DioExceptionType.receiveTimeout:
        case DioExceptionType.sendTimeout:
          return 'サーバーとの通信がタイムアウトしました';
        case DioExceptionType.connectionError:
          return 'ネットワークに接続できません';
        case DioExceptionType.badResponse:
          final code = e.response?.statusCode ?? 0;
          switch (code) {
            case 401:
              return '認証に失敗しました（トークン期限切れ）';
            case 403:
              return '地域外のため再生できません';
            case 404:
              return 'ストリームが見つかりません';
            default:
              return 'サーバーエラーが発生しました ($code)';
          }
        default:
          return '通信エラーが発生しました';
      }
    }
    return e.toString().replaceFirst('Exception: ', '');
  }

  /// 一時停止
  Future<void> pause() => _service.pause();

  /// 再開
  Future<void> resume() => _service.resume();

  /// 停止
  Future<void> stop() async {
    await _service.stop();
    state = const PlayerState();
  }

  /// シーク
  Future<void> seek(Duration position) => _service.seek(position);

  /// 再生/一時停止のトグル
  Future<void> togglePlayPause() async {
    if (_service.isPlaying) {
      await pause();
    } else {
      await resume();
    }
  }
}

/// PlayerNotifier の Provider
final playerProvider =
    StateNotifierProvider<PlayerNotifier, PlayerState>((ref) {
  final service = ref.read(playerServiceProvider);
  final apiClient = ref.read(apiClientProvider);
  return PlayerNotifier(service, apiClient, ref);
});
