import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/models/program.dart';
import '../../core/models/station.dart';
import '../stations/station_repository.dart';

/// 日別番組表 Provider
final dailyProgramsProvider =
    FutureProvider.family<List<Program>, (String stationId, DateTime date)>(
  (ref, params) async {
    final client = ref.read(apiClientProvider);
    return client.getDailyPrograms(params.$1, params.$2);
  },
);

/// 全放送局の現在放送中の番組（指定エリア）
/// 初回は即時に取得し、以降5分ごとに自動更新する
final nowPlayingProvider =
    StreamProvider.family<List<(Station, Program?)>, String>(
  (ref, areaId) {
    return _nowPlayingStream(ref, areaId);
  },
);

Stream<List<(Station, Program?)>> _nowPlayingStream(
    Ref ref, String areaId) async* {
  var hasValue = false;
  while (true) {
    try {
      final value = await _fetchNowPlaying(ref, areaId);
      hasValue = true;
      yield value;
    } catch (e) {
      // 初回失敗はエラーとして伝播（UIのエラー分岐に表示）
      if (!hasValue) rethrow;
      // 2回目以降の失敗はスキップし、前回の値を保持し続ける
      debugPrint('[nowPlayingProvider] 更新失敗: $e');
    }
    await Future.delayed(const Duration(minutes: 5));
  }
}

Future<List<(Station, Program?)>> _fetchNowPlaying(
    Ref ref, String areaId) async {
  final client = ref.read(apiClientProvider);
  final stations = await client.getStationsByAreaCached(areaId);
  final now = DateTime.now();

  // 全放送局を並列で取得（直列だと109局分の応答待ちで遅すぎる）
  final futures = stations.map((station) async {
    try {
      final programs = await client.getDailyPrograms(station.id, now);
      final currentProgram = programs.cast<Program?>().firstWhere(
        (p) => p?.isOnAir ?? false,
        orElse: () => null,
      );
      return (station, currentProgram);
    } catch (e) {
      debugPrint('[nowPlayingProvider] ${station.id}(${station.name}) の番組取得失敗: $e');
      return (station, null);
    }
  });

  return Future.wait(futures);
}
