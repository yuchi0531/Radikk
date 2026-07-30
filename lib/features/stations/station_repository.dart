import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/models/station.dart';
import '../../core/network/radiko_api_client.dart';

/// RadikoApiClient Provider
final apiClientProvider = Provider<RadikoApiClient>((ref) => RadikoApiClient());

/// 全放送局一覧
final allStationsProvider = FutureProvider<List<Station>>((ref) async {
  final client = ref.read(apiClientProvider);
  return client.getStations();
});

/// 指定エリアの放送局一覧
final stationsByAreaProvider = FutureProvider.family<List<Station>, String>(
  (ref, areaId) async {
    final client = ref.read(apiClientProvider);
    return client.getStationsByArea(areaId);
  },
);
