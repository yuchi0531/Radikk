/// 番組表グリッドでの番組位置計算
/// 縦軸=時間（左上原点）、横軸=放送局 のレイアウトで利用する
library;

/// 番組のグリッド内位置・高さ
class ProgramSlot {
  final double top;
  final double height;
  final bool visible;

  const ProgramSlot({
    required this.top,
    required this.height,
    required this.visible,
  });
}

/// 表示範囲内で番組の位置(top)と高さを計算する
///
/// - [rangeStartUtc] / [rangeEndUtc]: 表示範囲（UTC）
/// - 範囲外の番組は [ProgramSlot.visible] = false を返す
/// - 途中から始まる/途中で終わる番組は範囲にクリップする
/// - 高さは [minHeight] 未満にならないよう保証する
ProgramSlot computeProgramSlot({
  required DateTime startTime,
  required DateTime endTime,
  required DateTime rangeStartUtc,
  required DateTime rangeEndUtc,
  required double pxPerHour,
  required double totalHeight,
  double minHeight = 12,
}) {
  // 完全に範囲外なら非表示（境界ちょうども範囲外として扱う）
  if (!endTime.isAfter(rangeStartUtc) || !startTime.isBefore(rangeEndUtc)) {
    return const ProgramSlot(top: 0, height: 0, visible: false);
  }

  var top = startTime.isBefore(rangeStartUtc)
      ? 0.0
      : (startTime.difference(rangeStartUtc).inSeconds / 3600) * pxPerHour;
  var bottom = endTime.isAfter(rangeEndUtc)
      ? totalHeight
      : (endTime.difference(rangeStartUtc).inSeconds / 3600) * pxPerHour;
  var height = bottom - top;
  if (height < minHeight) height = minHeight;
  if (top + height > totalHeight) height = totalHeight - top;

  return ProgramSlot(top: top, height: height, visible: true);
}

/// 現在時刻ラインを表示すべきか（表示範囲内のときのみ true）
bool shouldShowNowLine({
  required DateTime nowUtc,
  required DateTime rangeStartUtc,
  required DateTime rangeEndUtc,
}) {
  return !nowUtc.isBefore(rangeStartUtc) && nowUtc.isBefore(rangeEndUtc);
}

/// 現在時刻ラインの Y 位置（px）
double computeNowLineY({
  required DateTime nowUtc,
  required DateTime rangeStartUtc,
  required double pxPerHour,
}) {
  return (nowUtc.difference(rangeStartUtc).inSeconds / 3600) * pxPerHour;
}