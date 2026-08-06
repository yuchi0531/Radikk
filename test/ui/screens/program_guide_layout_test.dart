import 'package:flutter_test/flutter_test.dart';
import 'package:radikk/ui/screens/program_guide_layout.dart';

void main() {
  // 表示範囲: 2026-08-05 JST 05:00 〜 翌 05:00 (UTC基準)
  final rangeStartUtc = DateTime.utc(2026, 8, 4, 20); // JST 5:00
  final rangeEndUtc = DateTime.utc(2026, 8, 5, 20); // JST 翌5:00
  const pxPerHour = 60.0;
  const totalHeight = 24 * 60.0;

  group('computeProgramSlot', () {
    test('範囲内の番組は正しい位置に配置される', () {
      // 7:00-8:00 JST = UTC 前日22:00-23:00
      final slot = computeProgramSlot(
        startTime: DateTime.utc(2026, 8, 4, 22),
        endTime: DateTime.utc(2026, 8, 4, 23),
        rangeStartUtc: rangeStartUtc,
        rangeEndUtc: rangeEndUtc,
        pxPerHour: pxPerHour,
        totalHeight: totalHeight,
      );
      expect(slot.visible, isTrue);
      expect(slot.top, closeTo(2 * 60.0, 0.1)); // 5時起点で2時間後
      expect(slot.height, closeTo(60.0, 0.1));
    });

    test('5時ちょうど開始の番組は top=0', () {
      final slot = computeProgramSlot(
        startTime: rangeStartUtc,
        endTime: DateTime.utc(2026, 8, 4, 21),
        rangeStartUtc: rangeStartUtc,
        rangeEndUtc: rangeEndUtc,
        pxPerHour: pxPerHour,
        totalHeight: totalHeight,
      );
      expect(slot.visible, isTrue);
      expect(slot.top, 0);
    });

    test('開始境界ちょうどの番組は非表示', () {
      // endTime == rangeStartUtc（ちょうど表示開始時刻に終了）
      final slot = computeProgramSlot(
        startTime: DateTime.utc(2026, 8, 4, 18),
        endTime: rangeStartUtc,
        rangeStartUtc: rangeStartUtc,
        rangeEndUtc: rangeEndUtc,
        pxPerHour: pxPerHour,
        totalHeight: totalHeight,
      );
      expect(slot.visible, isFalse);
    });

    test('終了境界ちょうどの番組は非表示', () {
      // startTime == rangeEndUtc（ちょうど表示終了時刻に開始）
      final slot = computeProgramSlot(
        startTime: rangeEndUtc,
        endTime: DateTime.utc(2026, 8, 5, 21),
        rangeStartUtc: rangeStartUtc,
        rangeEndUtc: rangeEndUtc,
        pxPerHour: pxPerHour,
        totalHeight: totalHeight,
      );
      expect(slot.visible, isFalse);
    });

    test('境界ちょうど開始の番組（startTime == rangeStartUtc）は表示される', () {
      // startTime == rangeStartUtc かつ endTime も範囲内の正常ケース
      final slot = computeProgramSlot(
        startTime: rangeStartUtc,
        endTime: DateTime.utc(2026, 8, 4, 22),
        rangeStartUtc: rangeStartUtc,
        rangeEndUtc: rangeEndUtc,
        pxPerHour: pxPerHour,
        totalHeight: totalHeight,
      );
      expect(slot.visible, isTrue);
      expect(slot.top, 0);
      expect(slot.height, closeTo(2 * 60.0, 0.1));
    });

    test('範囲より前の番組は非表示', () {
      final slot = computeProgramSlot(
        startTime: DateTime.utc(2026, 8, 4, 10),
        endTime: DateTime.utc(2026, 8, 4, 15),
        rangeStartUtc: rangeStartUtc,
        rangeEndUtc: rangeEndUtc,
        pxPerHour: pxPerHour,
        totalHeight: totalHeight,
      );
      expect(slot.visible, isFalse);
    });

    test('範囲より後の番組は非表示', () {
      final slot = computeProgramSlot(
        startTime: DateTime.utc(2026, 8, 5, 21),
        endTime: DateTime.utc(2026, 8, 5, 22),
        rangeStartUtc: rangeStartUtc,
        rangeEndUtc: rangeEndUtc,
        pxPerHour: pxPerHour,
        totalHeight: totalHeight,
      );
      expect(slot.visible, isFalse);
    });

    test('途中から始まる番組は上をクリップされる', () {
      // 3:00-6:00 JST = UTC 前日18:00-21:00（開始が範囲前）
      final slot = computeProgramSlot(
        startTime: DateTime.utc(2026, 8, 4, 18),
        endTime: DateTime.utc(2026, 8, 4, 21),
        rangeStartUtc: rangeStartUtc,
        rangeEndUtc: rangeEndUtc,
        pxPerHour: pxPerHour,
        totalHeight: totalHeight,
      );
      expect(slot.visible, isTrue);
      expect(slot.top, 0);
      expect(slot.height, closeTo(60.0, 0.1)); // 6:00までの1時間分
    });

    test('範囲の終端をまたぐ番組は下をクリップされる', () {
      // 23:00-30:00(翌6:00) JST = UTC 翌14:00-21:00（終了が範囲後）
      final slot = computeProgramSlot(
        startTime: DateTime.utc(2026, 8, 5, 14),
        endTime: DateTime.utc(2026, 8, 5, 21),
        rangeStartUtc: rangeStartUtc,
        rangeEndUtc: rangeEndUtc,
        pxPerHour: pxPerHour,
        totalHeight: totalHeight,
      );
      expect(slot.visible, isTrue);
      expect(slot.top, closeTo(18 * 60.0, 0.1));
      // 終端(翌5:00)までにクリップ → 5時起点18時間〜24時間の6時間分
      expect(slot.height, closeTo(6 * 60.0, 0.1));
      expect(slot.top + slot.height, closeTo(totalHeight, 0.1));
    });

    test('高さが極端に小さい番組は最小高さになる', () {
      final slot = computeProgramSlot(
        startTime: DateTime.utc(2026, 8, 4, 22, 0),
        endTime: DateTime.utc(2026, 8, 4, 22, 1), // 1分
        rangeStartUtc: rangeStartUtc,
        rangeEndUtc: rangeEndUtc,
        pxPerHour: pxPerHour,
        totalHeight: totalHeight,
        minHeight: 12,
      );
      expect(slot.visible, isTrue);
      expect(slot.height, 12);
    });
  });

  group('shouldShowNowLine / computeNowLineY', () {
    test('現在時刻が表示範囲内なら true', () {
      // 範囲内: 2026-08-05 10:00 JST = UTC 01:00
      final now = DateTime.utc(2026, 8, 5, 1);
      expect(
        shouldShowNowLine(
          nowUtc: now,
          rangeStartUtc: rangeStartUtc,
          rangeEndUtc: rangeEndUtc,
        ),
        isTrue,
      );
    });

    test('範囲外なら false', () {
      final before = DateTime.utc(2026, 8, 4, 12);
      final after = DateTime.utc(2026, 8, 5, 22);
      expect(
        shouldShowNowLine(
          nowUtc: before,
          rangeStartUtc: rangeStartUtc,
          rangeEndUtc: rangeEndUtc,
        ),
        isFalse,
      );
      expect(
        shouldShowNowLine(
          nowUtc: after,
          rangeStartUtc: rangeStartUtc,
          rangeEndUtc: rangeEndUtc,
        ),
        isFalse,
      );
    });

    test('Y位置が時間経過に比例する', () {
      // 5時開始+3時間 = 180分 → 3時間分のpx
      final now = DateTime.utc(2026, 8, 4, 23); // JST 8:00
      final y = computeNowLineY(
        nowUtc: now,
        rangeStartUtc: rangeStartUtc,
        pxPerHour: pxPerHour,
      );
      expect(y, closeTo(3 * 60.0, 0.1));
    });
  });
}