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
final nowPlayingProvider =
    FutureProvider.family<List<(Station, Program?)>, String>(
  (ref, areaId) async {
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
  },
);
