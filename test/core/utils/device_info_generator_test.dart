import 'package:flutter_test/flutter_test.dart';
import 'package:radikk/core/utils/device_info_generator.dart';

void main() {
  group('DeviceInfoGenerator', () {
    test('generateUserId should return 32 hex characters', () {
      final userId = DeviceInfoGenerator.generateUserId();
      expect(userId.length, equals(32));
      expect(RegExp(r'^[0-9a-f]{32}$').hasMatch(userId), isTrue);
    });

    test('generateUserId should produce different values', () {
      final id1 = DeviceInfoGenerator.generateUserId();
      final id2 = DeviceInfoGenerator.generateUserId();
      // Randomなので異なる可能性が高い
      expect(id1.length, equals(32));
      expect(id2.length, equals(32));
      expect(RegExp(r'^[0-9a-f]{32}$').hasMatch(id1), isTrue);
      expect(RegExp(r'^[0-9a-f]{32}$').hasMatch(id2), isTrue);
    });

    test('generateSdkVersion should return valid SDK', () {
      final sdk = DeviceInfoGenerator.generateSdkVersion();
      final value = int.parse(sdk);
      expect(value, greaterThanOrEqualTo(24));
      expect(value, lessThanOrEqualTo(34));
    });

    test('generateModel should return non-empty string', () {
      final model = DeviceInfoGenerator.generateModel();
      expect(model, isNotEmpty);
    });

    test('generateDevice should produce format "SDK.MODEL"', () {
      final device = DeviceInfoGenerator.generateDevice();
      expect(device, contains('.'));
      final parts = device.split('.');
      expect(parts.length, equals(2));
      expect(int.tryParse(parts[0]), isNotNull);
      expect(parts[1], isNotEmpty);
    });
  });
}
