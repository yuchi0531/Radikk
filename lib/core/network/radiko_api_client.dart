import 'package:dio/dio.dart';
import 'package:xml/xml.dart';
import '../constants/api_endpoints.dart';
import '../models/program.dart';
import '../models/station.dart';
import '../utils/radiko_time.dart';
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
      // radikoのXMLでは id, name, area_id 等は属性ではなく子要素
      final id = element.getElement('id')?.innerText ?? '';
      final name = element.getElement('name')?.innerText ?? '';
      final banner = element.getElement('banner')?.innerText;
      final href = element.getElement('href')?.innerText;

      // area_idの子要素を取得
      final areaIds = element
          .findElements('area_id')
          .map((n) => n.innerText)
          .toList();

      // logo（224x100）を取得
      final allLogos = element.findAllElements('logo').toList();
      final logoUrl = allLogos.isNotEmpty
          ? (allLogos.firstWhere(
              (l) =>
                  l.getAttribute('width') == '224' &&
                  l.getAttribute('height') == '100',
              orElse: () => allLogos.first,
            ).innerText)
          : null;

      final station = Station(
        id: id,
        name: name,
        logoUrl: logoUrl,
        bannerUrl: banner,
        detailUrl: href,
        areaIds: areaIds,
      );
      stations.add(station);
    }

    return stations;
  }

  /// 特定エリアの放送局一覧を取得
  Future<List<Station>> getStationsByArea(String areaId) async {
    final allStations = await getStations();
    return allStations.where((s) => s.areaIds.contains(areaId)).toList();
  }

  /// 放送局一覧をキャッシュするための静的フィールド
  static List<Station>? _stationsCache;
  static DateTime? _stationsCacheAt;

  /// キャッシュ付き放送局一覧（1時間キャッシュ）
  Future<List<Station>> getStationsCached() async {
    final now = DateTime.now();
    if (_stationsCache != null &&
        _stationsCacheAt != null &&
        now.difference(_stationsCacheAt!) < const Duration(hours: 1)) {
      return _stationsCache!;
    }
    final stations = await getStations();
    _stationsCache = stations;
    _stationsCacheAt = now;
    return stations;
  }

  /// 特定エリアの放送局一覧を取得（キャッシュ付き）
  Future<List<Station>> getStationsByAreaCached(String areaId) async {
    final allStations = await getStationsCached();
    return allStations.where((s) => s.areaIds.contains(areaId)).toList();
  }

  /// ストリーム設定を取得（playlist_create_url を含む）
  /// GET https://radiko.jp/v3/station/stream/pc_html5/{stationId}.xml
  ///
  /// ライブストリーム用: areafree="1" のURLを選択（エリア内アクセス）
  /// タイムフリー用: timefree="1" かつ areafree="0" のURLを選択
  Future<String> getPlaylistCreateUrl(String stationId,
      {bool timefree = false}) async {
    final url = '${ApiEndpoints.stationStream}/$stationId.xml';
    final response = await _safeApiCall(() => _dio.apiClient.get(url));
    final document = XmlDocument.parse(response.data.toString());

    final urlElements = document.findAllElements('url');
    if (urlElements.isEmpty) {
      throw Exception('playlist_create_url が見つかりません: $stationId');
    }

    // timefree再生の場合は areafree="0" かつ timefree="1" のURLを選択
    // ライブ再生の場合は areafree="1" のURLを選択
    XmlElement? selectedUrl;
    if (timefree) {
      // areafree="0" かつ timefree="1"
      selectedUrl = urlElements.firstWhere(
        (e) =>
            e.getAttribute('areafree') == '0' &&
            e.getAttribute('timefree') == '1',
        orElse: () => urlElements.first,
      );
    } else {
      // areafree="1" を優先（エリア内ライブストリーム）
      selectedUrl = urlElements.firstWhere(
        (e) => e.getAttribute('areafree') == '1',
        orElse: () => urlElements.first,
      );
    }

    final playlistUrl =
        selectedUrl.getElement('playlist_create_url')?.innerText.trim();
    if (playlistUrl == null || playlistUrl.isEmpty) {
      throw Exception('playlist_create_url が空です: $stationId');
    }
    return playlistUrl;
  }

  /// 日別番組表をキャッシュするための静的フィールド
  static final Map<String, List<Program>> _dailyCache = {};
  static final Map<String, DateTime> _dailyCacheAt = {};

  /// 日別番組表を取得
  /// GET https://api.radiko.jp/program/v4/date/{YYYYMMDD}/station/{stationId}.json
  Future<List<Program>> getDailyPrograms(
      String stationId, DateTime date) async {
    // 日付はJST基準で表記される
    final jstDate = date.toUtc().add(jstOffset);
    final dateStr =
        '${jstDate.year}${jstDate.month.toString().padLeft(2, '0')}${jstDate.day.toString().padLeft(2, '0')}';
    final cacheKey = '$stationId-$dateStr';

    // 1時間キャッシュ
    final now = DateTime.now();
    final cachedAt = _dailyCacheAt[cacheKey];
    if (cachedAt != null &&
        now.difference(cachedAt) < const Duration(hours: 1)) {
      return _dailyCache[cacheKey]!;
    }

    // キャッシュがたまりすぎないように制限（メモリ保護）
    if (_dailyCache.length > 300) {
      _dailyCache.clear();
      _dailyCacheAt.clear();
    }

    final url = '${ApiEndpoints.dailyProgram}/$dateStr/station/$stationId.json';

    final response =
        await _safeApiCall(() => _dio.authClient.get(url));
    final data = response.data as Map<String, dynamic>;

    final programs = <Program>[];
    final stationsData = data['stations'] as List<dynamic>?;
    if (stationsData == null || stationsData.isEmpty) return programs;

    for (final stationData in stationsData) {
      final stationIdFromData =
          stationData['station_id']?.toString() ?? stationId;
      // APIレスポンス: stations[].programs.program[]
      final programsWrapper =
          stationData['programs'] as Map<String, dynamic>?;
      final progs = programsWrapper?['program'] as List<dynamic>?;
      if (progs == null) continue;

      for (final prog in progs) {
        final progData = prog as Map<String, dynamic>;
        // ft/to は "20260730050000" 形式 → DateTime に変換
        final startTime = parseRadikoDateTime(progData['ft']);
        final endTime = parseRadikoDateTime(progData['to']);
        programs.add(Program(
          id: progData['episode_id']?.toString() ??
              progData['id']?.toString() ?? '',
          title: progData['title'] ?? '',
          description: progData['description'],
          personality: progData['performer'],
          imageUrl: progData['img'],
          stationId: stationIdFromData,
          stationName: stationData['station_name']?.toString() ?? '',
          startTime: startTime,
          endTime: endTime,
          duration: endTime.difference(startTime),
          infoUrl: progData['url'],
          shareUrl: progData['episode_id']?.toString(),
        ));
      }
    }

    _dailyCache[cacheKey] = programs;
    _dailyCacheAt[cacheKey] = now;
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
