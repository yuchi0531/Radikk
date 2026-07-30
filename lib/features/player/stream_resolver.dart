import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/radiko_api_client.dart';
import '../auth/auth_provider.dart';
import '../stations/station_repository.dart';

/// ストリームURL解決サービス
class StreamResolver {
  final RadikoApiClient _apiClient;

  StreamResolver(this._apiClient);

  /// ライブストリームのm3u8プレイリストURLを解決
  /// stationId → playlist_create_url → m3u8 URL
  Future<String> resolveLiveStreamUrl(String stationId, String authToken) async {
    final playlistCreateUrl = await _apiClient.getPlaylistCreateUrl(stationId);
    return playlistCreateUrl;
  }

  /// タイムフリーストリームのm3u8プレイリストURLを構築
  /// station_id, from, to からURLを生成
  Future<String> resolveTimefreeStreamUrl({
    required String stationId,
    required String authToken,
    required DateTime fromTime,
    required DateTime toTime,
    required String areaId,
  }) async {
    // まずstationのplaylist_create_urlを取得
    final playlistCreateUrl = await _apiClient.getPlaylistCreateUrl(stationId);

    // playlist_create_url にクエリパラメータを追加
    final fromStr = _formatDateTime(fromTime);
    final toStr = _formatDateTime(toTime);

    // URLにパラメータを追加
    final separator = playlistCreateUrl.contains('?') ? '&' : '?';
    return '$playlistCreateUrl${separator}station_id=$stationId'
        '&ft=$fromStr&to=$toStr&l=300';
  }

  String _formatDateTime(DateTime dt) {
    return '${dt.year}'
        '${dt.month.toString().padLeft(2, '0')}'
        '${dt.day.toString().padLeft(2, '0')}'
        '${dt.hour.toString().padLeft(2, '0')}'
        '${dt.minute.toString().padLeft(2, '0')}'
        '${dt.second.toString().padLeft(2, '0')}';
  }
}

/// StreamResolver Provider
final streamResolverProvider = Provider<StreamResolver>((ref) {
  final apiClient = ref.read(apiClientProvider);
  return StreamResolver(apiClient);
});

/// ライブストリームURL Provider
final liveStreamUrlProvider =
    FutureProvider.family<String?, String>((ref, stationId) async {
  final authState = ref.read(authStateProvider);
  final token = authState.valueOrNull;

  if (token == null) return null;

  final resolver = ref.read(streamResolverProvider);
  return resolver.resolveLiveStreamUrl(stationId, token.token);
});
