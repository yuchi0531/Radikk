import 'package:flutter_test/flutter_test.dart';
import 'package:radikk/core/models/program.dart';

void main() {
  group('Program', () {
    test('fromJson should parse correctly', () {
      final now = DateTime.now();
      final startTime = now.subtract(const Duration(hours: 1));
      final endTime = now.add(const Duration(hours: 1));

      final json = {
        'id': 'test_program_1',
        'title': 'テスト番組',
        'desc': 'テスト番組の説明',
        'pfm': 'テストパーソナリティ',
        'img': 'https://example.com/image.jpg',
        'ft': startTime.toIso8601String(),
        'to': endTime.toIso8601String(),
      };

      final program = Program.fromJson(json, 'TBS');

      expect(program.id, equals('test_program_1'));
      expect(program.title, equals('テスト番組'));
      expect(program.description, equals('テスト番組の説明'));
      expect(program.personality, equals('テストパーソナリティ'));
      expect(program.imageUrl, equals('https://example.com/image.jpg'));
      expect(program.stationId, equals('TBS'));
    });

    test('fromJson with minimal data should not throw', () {
      final json = {
        'id': 'minimal',
        'title': '最小番組',
        'ft': '2024-01-01T06:00:00.000',
        'to': '2024-01-01T07:00:00.000',
      };

      final program = Program.fromJson(json, 'TBS');
      expect(program.title, equals('最小番組'));
    });

    test('fromJson with invalid date should not throw', () {
      final json = {
        'id': 'bad_date',
        'title': '日付異常',
        'ft': 'invalid',
        'to': 'also_invalid',
      };

      final program = Program.fromJson(json, 'TBS');
      expect(program.title, equals('日付異常'));
    });

    test('isOnAir should be true during broadcast', () {
      final now = DateTime.now();
      final json = {
        'id': 'on_air',
        'title': '放送中',
        'ft': now.subtract(const Duration(minutes: 30)).toIso8601String(),
        'to': now.add(const Duration(minutes: 30)).toIso8601String(),
      };

      final program = Program.fromJson(json, 'TBS');
      expect(program.isOnAir, isTrue);
    });

    test('isOnAir should be false after broadcast', () {
      final now = DateTime.now();
      final json = {
        'id': 'ended',
        'title': '終了',
        'ft': now.subtract(const Duration(hours: 2)).toIso8601String(),
        'to': now.subtract(const Duration(hours: 1)).toIso8601String(),
      };

      final program = Program.fromJson(json, 'TBS');
      expect(program.isOnAir, isFalse);
    });

    test('isTimefreeAvailable should be true within 7 days', () {
      final now = DateTime.now();
      final json = {
        'id': 'timefree_ok',
        'title': 'タイムフリー可能',
        'ft': now.subtract(const Duration(hours: 3)).toIso8601String(),
        'to': now.subtract(const Duration(hours: 2)).toIso8601String(),
      };

      final program = Program.fromJson(json, 'TBS');
      expect(program.isTimefreeAvailable, isTrue);
    });

    test('isTimefreeAvailable should be false before broadcast ends', () {
      final now = DateTime.now();
      final json = {
        'id': 'still_airing',
        'title': '放送中',
        'ft': now.subtract(const Duration(minutes: 30)).toIso8601String(),
        'to': now.add(const Duration(minutes: 30)).toIso8601String(),
      };

      final program = Program.fromJson(json, 'TBS');
      expect(program.isTimefreeAvailable, isFalse);
    });

    test('isTimefreeAvailable should be false after 7 days', () {
      final now = DateTime.now();
      final json = {
        'id': 'too_old',
        'title': '古すぎる',
        'ft': now.subtract(const Duration(days: 8)).toIso8601String(),
        'to': now.subtract(const Duration(days: 7, hours: 23)).toIso8601String(),
      };

      final program = Program.fromJson(json, 'TBS');
      expect(program.isTimefreeAvailable, isFalse);
    });
  });
}
