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

/// 週間番組表 Provider
final weeklyProgramsProvider =
    FutureProvider.family<List<Program>, String>(
  (ref, stationId) async {
    final client = ref.read(apiClientProvider);
    return client.getWeeklyPrograms(stationId);
  },
);

/// 全放送局の現在放送中の番組（指定エリア）
final nowPlayingProvider =
    FutureProvider.family<List<(Station, Program?)>, String>(
  (ref, areaId) async {
    final client = ref.read(apiClientProvider);
    final stations = await client.getStationsByArea(areaId);
    final now = DateTime.now();

    final results = <(Station, Program?)>[];
    for (final station in stations) {
      try {
        final programs = await client.getDailyPrograms(station.id, now);
        final currentProgram = programs.cast<Program?>().firstWhere(
          (p) => p?.isOnAir ?? false,
          orElse: () => null,
        );
        results.add((station, currentProgram));
      } catch (_) {
        results.add((station, null));
      }
    }
    return results;
  },
);
