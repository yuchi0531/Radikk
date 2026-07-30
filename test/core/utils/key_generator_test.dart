import 'package:flutter_test/flutter_test.dart';
import 'package:radikk/core/constants/app_keys.dart';
import 'package:radikk/core/utils/key_generator.dart';

void main() {
  group('KeyGenerator', () {
    test('generatePartialKey should produce valid base64', () {
      final result = KeyGenerator.generatePartialKey(0, 16);
      expect(result, isNotEmpty);
      // base64であることを確認
      expect(RegExp(r'^[A-Za-z0-9+/]+=*$').hasMatch(result), isTrue);
    });

    test('generatePartialKey with offset should produce different result', () {
      final key1 = KeyGenerator.generatePartialKey(0, 16);
      final key2 = KeyGenerator.generatePartialKey(16, 16);
      expect(key1, isNot(equals(key2)));
    });

    test('generatePartialKey should handle large offset', () {
      final result = KeyGenerator.generatePartialKey(1000, 64);
      expect(result, isNotEmpty);
    });

    test('generatePartialKey with negative offset should throw', () {
      expect(
        () => KeyGenerator.generatePartialKey(-1, 16),
        throwsArgumentError,
      );
    });

    test('generatePartialKey with zero length should throw', () {
      expect(
        () => KeyGenerator.generatePartialKey(0, 0),
        throwsArgumentError,
      );
    });

    test('generatePartialKey with negative length should throw', () {
      expect(
        () => KeyGenerator.generatePartialKey(0, -1),
        throwsArgumentError,
      );
    });

    test('fullKey should be non-empty', () {
      expect(AppKeys.fullKey, isNotEmpty);
      expect(AppKeys.fullKey.length, greaterThan(1000));
    });
  });
}
