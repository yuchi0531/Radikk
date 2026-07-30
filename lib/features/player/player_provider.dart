import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/radiko_api_client.dart';
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

  const PlayerState({
    this.status = PlayerStatus.idle,
    this.stationId,
    this.stationName,
    this.programTitle,
    this.position = Duration.zero,
    this.duration,
    this.isTimefree = false,
    this.errorMessage,
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
    );
  }
}

/// プレイヤー状態を管理するNotifier
class PlayerNotifier extends StateNotifier<PlayerState> {
  final PlayerService _service;
  final RadikoApiClient _apiClient;
  final Ref _ref;

  PlayerNotifier(this._service, this._apiClient, this._ref)
      : super(const PlayerState()) {
    _service.onStatusChanged = (status) {
      state = state.copyWith(status: status, clearError: true);
    };
    _service.onPositionChanged = (position, duration) {
      state = state.copyWith(position: position, duration: duration);
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
      clearError: true,
    );

    try {
      // 認証状態からトークンを取得（なければ認証を実行）
      final authState = _ref.read(authStateProvider);
      var token = authState.valueOrNull;
      if (token == null) {
        await _ref.read(authStateProvider.notifier).authenticate();
        final authState2 = _ref.read(authStateProvider);
        token = authState2.valueOrNull;
        if (token == null) {
          throw Exception('認証に失敗しました');
        }
      }

      final playlistUrl = await _apiClient.getPlaylistCreateUrl(stationId);

      await _service.playLive(
        url: playlistUrl,
        authToken: token.token,
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
      clearError: true,
    );

    try {
      // 認証状態からトークンを取得（なければ認証を実行）
      final authState = _ref.read(authStateProvider);
      var token = authState.valueOrNull;
      if (token == null) {
        await _ref.read(authStateProvider.notifier).authenticate();
        final authState2 = _ref.read(authStateProvider);
        token = authState2.valueOrNull;
        if (token == null) {
          throw Exception('認証に失敗しました');
        }
      }

      final playlistUrl = await _apiClient.getPlaylistCreateUrl(stationId);
      final fromStr = _formatDt(startTime);
      final toStr = _formatDt(endTime);

      final url =
          '$playlistUrl?station_id=$stationId&ft=$fromStr&to=$toStr&l=300';

      await _service.playTimefree(
        url: url,
        authToken: token.token,
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

  String _formatDt(DateTime dt) {
    return '${dt.year}'
        '${dt.month.toString().padLeft(2, '0')}'
        '${dt.day.toString().padLeft(2, '0')}'
        '${dt.hour.toString().padLeft(2, '0')}'
        '${dt.minute.toString().padLeft(2, '0')}'
        '${dt.second.toString().padLeft(2, '0')}';
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
