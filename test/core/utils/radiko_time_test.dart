import 'package:flutter_test/flutter_test.dart';
import 'package:radikk/core/utils/radiko_time.dart';

void main() {
  group('parseRadikoDateTime', () {
    test('14桁のradiko形式 (JST) をUTCに変換する', () {
      final dt = parseRadikoDateTime('20260730050000');
      // JST 2026-07-30 05:00:00 = UTC 2026-07-29 20:00:00
      expect(dt.isUtc, isTrue);
      expect(dt.year, 2026);
      expect(dt.month, 7);
      expect(dt.day, 29);
      expect(dt.hour, 20);
      expect(dt.minute, 0);
      expect(dt.second, 0);
    });

    test('日付をまたぐJST時刻も正しく変換する', () {
      // JST 2026-07-01 01:00:00 = UTC 2026-06-30 16:00:00
      final dt = parseRadikoDateTime('20260701010000');
      expect(dt.year, 2026);
      expect(dt.month, 6);
      expect(dt.day, 30);
      expect(dt.hour, 16);
    });

    test('ISO 8601形式もパースできる', () {
      const input = '2024-01-01T06:00:00.000';
      final dt = parseRadikoDateTime(input);
      // オフセットなしISOはローカルタイムとして解釈され、UTCに変換される
      expect(dt.isUtc, isTrue);
      expect(dt, DateTime.tryParse(input)?.toUtc());
    });

    test('無効な値は現在時刻(UTC)を返す', () {
      final dt = parseRadikoDateTime('invalid');
      expect(dt.isUtc, isTrue);
      expect(dt.difference(DateTime.now().toUtc()).inSeconds.abs(), lessThan(60));
    });

    test('nullは現在時刻(UTC)を返す', () {
      final dt = parseRadikoDateTime(null);
      expect(dt.isUtc, isTrue);
    });
  });

  group('formatRadikoDateTime', () {
    test('UTCのDateTimeをJSTの14桁形式に変換する', () {
      // UTC 2026-07-29 20:00:00 = JST 2026-07-30 05:00:00
      final str = formatRadikoDateTime(DateTime.utc(2026, 7, 29, 20, 0, 0));
      expect(str, '20260730050000');
    });

    test('ローカルタイムのDateTimeも絶対時刻としてJSTに変換する', () {
      // ローカルタイムで構築したDateTimeは toUtc() で絶対時刻に揃えてからJST表示する
      // 結果は環境のタイムゾーンに依存しない（絶対時刻が同じなら同じ文字列）
      final utc = DateTime.utc(2026, 7, 29, 20, 0, 0);
      final local = utc.toLocal();
      expect(formatRadikoDateTime(local), formatRadikoDateTime(utc));
    });

    test('パース→フォーマットの往復が一致する', () {
      const original = '20260730050000';
      final dt = parseRadikoDateTime(original);
      expect(formatRadikoDateTime(dt), original);
    });
  });
}
