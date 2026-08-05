import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/models/program.dart';
import '../stations/station_repository.dart';

/// 全放送局のタイムフリー可能な番組（指定エリア・指定日）
final timefreeAllStationsProvider = FutureProvider.family<
    List<(Program, String stationName)>, (String areaId, DateTime date)>(
  (ref, params) async {
    final client = ref.read(apiClientProvider);
    final stations = await client.getStationsByAreaCached(params.$1);

    // 全放送局を並列で取得
    final resultsPerStation = await Future.wait(
      stations.map((station) async {
        try {
          final programs = await client.getDailyPrograms(station.id, params.$2);
          return programs
              .where((p) => p.isTimefreeAvailable)
              .map((p) => (p, station.name))
              .toList();
        } catch (_) {
          // 取得失敗はスキップ
          return <(Program, String)>[];
        }
      }),
    );

    final results = resultsPerStation.expand((r) => r).toList();

    // 開始時刻順にソート
    results.sort((a, b) => a.$1.startTime.compareTo(b.$1.startTime));
    return results;
  },
);
