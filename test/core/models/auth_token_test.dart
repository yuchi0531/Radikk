import 'package:flutter_test/flutter_test.dart';
import 'package:radikk/core/models/auth_token.dart';

void main() {
  group('AuthToken', () {
    test('should not be expired when just created', () {
      final token = AuthToken(
        token: 'test_token',
        keyOffset: 0,
        keyLength: 16,
        createdAt: DateTime.now(),
      );
      expect(token.isExpired, isFalse);
    });

    test('should be expired after 4200 seconds', () {
      final token = AuthToken(
        token: 'test_token',
        keyOffset: 0,
        keyLength: 16,
        createdAt: DateTime.now().subtract(const Duration(seconds: 4201)),
      );
      expect(token.isExpired, isTrue);
    });

    test('should not be expired before 4200 seconds', () {
      final token = AuthToken(
        token: 'test_token',
        keyOffset: 0,
        keyLength: 16,
        createdAt: DateTime.now().subtract(const Duration(seconds: 4100)),
      );
      expect(token.isExpired, isFalse);
    });

    test('should store area info', () {
      final token = AuthToken(
        token: 'test_token',
        keyOffset: 100,
        keyLength: 32,
        areaId: 'JP13',
        areaName: '東京都',
        createdAt: DateTime.now(),
      );
      expect(token.areaId, equals('JP13'));
      expect(token.areaName, equals('東京都'));
    });
  });
}
