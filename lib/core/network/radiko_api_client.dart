import 'package:dio/dio.dart';
import 'package:xml/xml.dart';
import '../constants/api_endpoints.dart';
import '../models/program.dart';
import '../models/station.dart';
import 'dio_client.dart';

/// radiko API クライアント
class RadikoApiClient {
  final _dio = RadikoDioClient();

  /// 放送局一覧を取得（全エリア）
  /// GET https://radiko.jp/v3/station/region/full.xml
  Future<List<Station>> getStations() async {
    final response = await _safeApiCall(() =>
        _dio.apiClient.get(ApiEndpoints.stationRegionFull));

    final document = XmlDocument.parse(response.data.toString());

    final stations = <Station>[];
    for (final element in document.findAllElements('station')) {
      final attrs = <String, String>{};
      for (final attr in element.attributes) {
        attrs[attr.name.local] = attr.value;
      }

      // area_idをパース
      final areaIdNodes = element.findElements('area_id');
      final areaIds = areaIdNodes.map((n) => n.innerText).toList();

      final station = Station.fromXml(attrs);
      stations.add(station.copyWith(areaIds: areaIds));
    }

    return stations;
  }

  /// 特定エリアの放送局一覧を取得
  Future<List<Station>> getStationsByArea(String areaId) async {
    final allStations = await getStations();
    return allStations.where((s) => s.areaIds.contains(areaId)).toList();
  }

  /// ストリーム設定を取得（playlist_create_url を含む）
  /// GET https://radiko.jp/v3/station/stream/pc_html5/{stationId}.xml
  Future<String> getPlaylistCreateUrl(String stationId) async {
    final url = '${ApiEndpoints.stationStream}/$stationId.xml';
    final response = await _safeApiCall(() => _dio.apiClient.get(url));
    final document = XmlDocument.parse(response.data.toString());

    final urlElement =
        document.findAllElements('playlist_create_url').firstOrNull;
    if (urlElement == null) {
      throw Exception('playlist_create_url が見つかりません: $stationId');
    }
    return urlElement.innerText.trim();
  }

  /// 日別番組表を取得
  /// GET https://api.radiko.jp/program/v4/date/{YYYYMMDD}/station/{stationId}.json
  Future<List<Program>> getDailyPrograms(
      String stationId, DateTime date) async {
    final dateStr =
        '${date.year}${date.month.toString().padLeft(2, '0')}${date.day.toString().padLeft(2, '0')}';
    final url = '${ApiEndpoints.dailyProgram}/$dateStr/station/$stationId.json';

    final response = await _safeApiCall(() => _dio.apiClient.get(url));
    final data = response.data as Map<String, dynamic>;

    final programs = <Program>[];
    final stationsData = data['stations'] as List<dynamic>?;
    if (stationsData == null || stationsData.isEmpty) return programs;

    for (final stationData in stationsData) {
      final stationIdFromData =
          stationData['station_id']?.toString() ?? stationId;
      final progs = stationData['progs'] as List<dynamic>?;
      if (progs == null) continue;

      for (final prog in progs) {
        programs.add(Program.fromJson(
          prog as Map<String, dynamic>,
          stationIdFromData,
        ));
      }
    }

    return programs;
  }

  /// 週間番組表を取得
  /// GET https://api.radiko.jp/program/v3/weekly/{stationId}.xml
  Future<List<Program>> getWeeklyPrograms(String stationId) async {
    final url = '${ApiEndpoints.weeklyProgram}/$stationId.xml';
    final response = await _safeApiCall(() => _dio.apiClient.get(url));
    final document = XmlDocument.parse(response.data.toString());

    final programs = <Program>[];
    final progElements = document.findAllElements('prog');
    for (final prog in progElements) {
      try {
        final attrs = <String, String>{};
        for (final attr in prog.attributes) {
          attrs[attr.name.local] = attr.value;
        }

        final title =
            prog.findElements('title').firstOrNull?.innerText ?? '';
        final ft = attrs['ft'] ?? '';
        final to = attrs['to'] ?? '';
        final id = attrs['id'] ?? '';

        programs.add(Program.fromJson({
          'id': id,
          'title': title,
          'ft': ft,
          'to': to,
          'desc': prog.findElements('desc').firstOrNull?.innerText,
          'pfm': prog.findElements('pfm').firstOrNull?.innerText,
          'img': prog.findElements('img').firstOrNull?.innerText,
          'info': prog.findElements('info').firstOrNull?.innerText,
          'share': prog.findElements('share').firstOrNull?.innerText,
        }, stationId));
      } catch (_) {
        // パースエラーはスキップ
      }
    }

    return programs;
  }

  /// API呼び出しのラッパー（エラーハンドリング統一）
  Future<Response<T>> _safeApiCall<T>(
      Future<Response<T>> Function() call) async {
    try {
      return await call();
    } on DioException catch (e) {
      final code = e.response?.statusCode ?? 0;
      switch (code) {
        case 401:
          throw ApiException('認証エラー（トークン期限切れ）', code);
        case 403:
          throw ApiException('アクセス禁止（地域外）', code);
        case 404:
          throw ApiException('リソースが見つかりません', code);
        default:
          throw ApiException('APIエラー (${e.message})', code);
      }
    } catch (e) {
      throw ApiException('ネットワークエラー: $e', 0);
    }
  }
}

/// API例外
class ApiException implements Exception {
  final String message;
  final int statusCode;

  ApiException(this.message, this.statusCode);

  @override
  String toString() => 'ApiException: $message (status: $statusCode)';
}
