import 'dart:math';
import '../constants/gps_coords.dart';
import '../constants/area_map.dart';

/// 指定エリア内のランダムGPS座標を生成する
class GpsGenerator {
  static final _random = Random();

  /// 指定エリアIDに対応するGPS座標を生成
  /// フォーマット: "緯度,経度,gps"
  /// 例: "35.689488,139.691706,gps"
  static String generateForArea(String areaId) {
    final area = AreaMap.areas.firstWhere(
      (a) => a.id == areaId,
      orElse: () => AreaMap.areas.firstWhere((a) => a.id == 'JP13'),
    );

    final coords = GpsCoords.coords[area.japanese] ?? [35.689488, 139.691706];

    var lat = coords[0];
    var lon = coords[1];

    // ±0.025度のランダムオフセット（約2.7km）
    lat += _random.nextDouble() / 40.0 * (_random.nextBool() ? 1 : -1);
    lon += _random.nextDouble() / 40.0 * (_random.nextBool() ? 1 : -1);

    return '${lat.toStringAsFixed(6)},${lon.toStringAsFixed(6)},gps';
  }

  /// 都道府県名からGPS座標を生成
  static String generateForPrefecture(String prefecture) {
    final coords = GpsCoords.coords[prefecture] ?? [35.689488, 139.691706];
    var lat = coords[0];
    var lon = coords[1];

    lat += _random.nextDouble() / 40.0 * (_random.nextBool() ? 1 : -1);
    lon += _random.nextDouble() / 40.0 * (_random.nextBool() ? 1 : -1);

    return '${lat.toStringAsFixed(6)},${lon.toStringAsFixed(6)},gps';
  }
}
