import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/models/program.dart';
import '../stations/station_repository.dart';

/// 指定放送局の過去7日間の全番組一覧
final timefreeProgramsProvider =
    FutureProvider.family<List<Program>, (String stationId, DateTime date)>(
  (ref, params) async {
    final client = ref.read(apiClientProvider);
    return client.getDailyPrograms(params.$1, params.$2);
  },
);

/// 全放送局のタイムフリー可能な番組（指定エリア・指定日）
final timefreeAllStationsProvider = FutureProvider.family<
    List<(Program, String stationName)>, (String areaId, DateTime date)>(
  (ref, params) async {
    final client = ref.read(apiClientProvider);
    final stations = await client.getStationsByArea(params.$1);

    final results = <(Program, String stationName)>[];
    for (final station in stations) {
      try {
        final programs = await client.getDailyPrograms(station.id, params.$2);
        for (final p in programs) {
          if (p.isTimefreeAvailable) {
            results.add((p, station.name));
          }
        }
      } catch (_) {
        // 取得失敗はスキップ
      }
    }

    // 開始時刻順にソート
    results.sort((a, b) => a.$1.startTime.compareTo(b.$1.startTime));
    return results;
  },
);
