/// radiko API エンドポイント定数
class ApiEndpoints {
  static const String baseUrl = 'https://api.radiko.jp';
  static const String oldBaseUrl = 'https://radiko.jp';

  // Auth
  static const String auth1 = '/v2/api/auth1';
  static const String auth2 = '/v2/api/auth2';
  static const String authCheck = '/v2/api/auth_check';

  // Station
  static const String stationRegionFull = '/v3/station/region/full.xml';
  static const String stationStream = '/v3/station/stream/pc_html5';

  // Program
  static const String weeklyProgram = '/program/v3/weekly';
  static const String dailyProgram = '/program/v4/date';

  // Now playing
  static const String nowOnAir = '/music/api/v1/noas';
}
