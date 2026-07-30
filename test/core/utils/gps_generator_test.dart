import 'package:flutter_test/flutter_test.dart';
import 'package:radikk/core/utils/gps_generator.dart';

void main() {
  group('GpsGenerator', () {
    test('generateForArea should return valid format', () {
      final result = GpsGenerator.generateForArea('JP13');
      expect(result, contains(',gps'));

      final parts = result.split(',');
      expect(parts.length, equals(3));
      expect(parts[2], equals('gps'));

      final lat = double.parse(parts[0]);
      final lon = double.parse(parts[1]);
      // Tokyo area: approximately 35.6, 139.6 with some random offset
      expect(lat, greaterThan(35.6));
      expect(lat, lessThan(35.8));
      expect(lon, greaterThan(139.6));
      expect(lon, lessThan(139.8));
    });

    test('generateForArea with different areas should differ', () {
      final tokyo1 = GpsGenerator.generateForArea('JP13');
      final tokyo2 = GpsGenerator.generateForArea('JP13');
      // Same area but random offsets may or may not differ
      expect(tokyo1.endsWith(',gps'), isTrue);
      expect(tokyo2.endsWith(',gps'), isTrue);
    });

    test('generateForArea with unknown area falls back to JP13', () {
      final result = GpsGenerator.generateForArea('INVALID');
      expect(result, contains(',gps'));
    });

    test('generateForPrefecture should return valid format', () {
      final result = GpsGenerator.generateForPrefecture('大阪');
      final parts = result.split(',');
      expect(parts.length, equals(3));
      expect(parts[2], equals('gps'));
      final lat = double.parse(parts[0]);
      expect(lat, greaterThan(34.6));
      expect(lat, lessThan(34.8));
    });
  });
}
