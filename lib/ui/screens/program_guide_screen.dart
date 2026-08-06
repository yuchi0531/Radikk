import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/models/program.dart';
import '../../core/models/station.dart';
import '../../core/utils/radiko_time.dart';
import '../../features/auth/auth_provider.dart';
import '../../features/player/player_provider.dart';
import '../../features/programs/program_repository.dart';
import '../../features/stations/station_repository.dart';
import '../widgets/area_selector.dart';
import 'program_guide_layout.dart';

/// 番組表レイアウト定数
/// 縦軸=時間（radikoは5時起点）、横軸=放送局 のグリッド
const double _pxPerHour = 56.0; // 1時間あたりの高さ(px)
const double _timeColumnWidth = 44.0; // 左端の時間ラベル列の幅
const double _stationColumnWidth = 132.0; // 各放送局カラムの幅
const double _headerHeight = 40.0; // 局名ヘッダー行の高さ
const int _startHourJst = 5; // 番組表の開始時刻(JST、radikoは5時起点)
const int _totalHours = 24; // 表示する時間数（5時〜翌5時）

class ProgramGuideScreen extends ConsumerStatefulWidget {
  const ProgramGuideScreen({super.key});

  @override
  ConsumerState<ProgramGuideScreen> createState() => _ProgramGuideScreenState();
}

class _ProgramGuideScreenState extends ConsumerState<ProgramGuideScreen> {
  DateTime _selectedDate = DateTime.now();
  Timer? _timer;
  final ScrollController _hScroll = ScrollController();
  final ScrollController _vScroll = ScrollController();

  @override
  void initState() {
    super.initState();
    // 現在放送中の番組が属する番組表の日付で初期化
    _selectedDate = _currentGuideDate;
    // 現在時刻ライン・放送中ハイライトを定期更新
    _timer = Timer.periodic(const Duration(seconds: 30), (_) => setState(() {}));
  }

  @override
  void dispose() {
    _timer?.cancel();
    _hScroll.dispose();
    _vScroll.dispose();
    super.dispose();
  }

  /// 今日（JST基準）の日付
  DateTime get _jstToday {
    final jstNow = DateTime.now().toUtc().add(jstOffset);
    return DateTime(jstNow.year, jstNow.month, jstNow.day);
  }

  /// 現在の放送が属する番組表の日付（JST 5時起点）
  /// 現在JST時刻が 0:00〜4:59 の間は前日が「今日の放送」の属する日
  DateTime get _currentGuideDate {
    final jstNow = DateTime.now().toUtc().add(jstOffset);
    final today = DateTime(jstNow.year, jstNow.month, jstNow.day);
    // JST 5:00 より前（0:00〜4:59）は前日の放送枠（前日5:00〜当日5:00）に属する
    return jstNow.hour < 5 ? today.subtract(const Duration(days: 1)) : today;
  }

  @override
  Widget build(BuildContext context) {
    final areaId = ref.watch(selectedAreaProvider);
    final stationsAsync = ref.watch(stationsByAreaProvider(areaId));

    return Column(
      children: [
        const AreaSelector(),
        SizedBox(
          height: 48,
          child: ListView.builder(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 8),
            itemCount: 7,
            itemBuilder: (context, index) {
              final date = _jstToday.add(Duration(days: index));
              final isSelected = _isSameDay(date, _selectedDate);
              return Padding(
                padding: const EdgeInsets.symmetric(horizontal: 4),
                child: ChoiceChip(
                  label: Text(_formatDate(date)),
                  selected: isSelected,
                  onSelected: (_) {
                    setState(() {
                      _selectedDate = date;
                    });
                  },
                ),
              );
            },
          ),
        ),
        const Divider(height: 1),
        Expanded(
          child: stationsAsync.when(
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (e, _) => Center(child: Text('エラー: $e')),
            data: (stations) {
              if (stations.isEmpty) {
                return const Center(child: Text('放送局がありません'));
              }
              return _buildGuide(stations);
            },
          ),
        ),
      ],
    );
  }

  /// 番組表グリッド本体
  Widget _buildGuide(List<Station> stations) {
    // 各局の番組を取得
    final programsByStation = <Station, List<Program>>{};
    var anyLoading = false;
    for (final station in stations) {
      final programsAsync =
          ref.watch(dailyProgramsProvider((station.id, _selectedDate)));
      if (programsAsync.isLoading) {
        anyLoading = true;
        continue;
      }
      programsByStation[station] = programsAsync.value ?? [];
    }

    if (anyLoading) {
      return const Center(child: CircularProgressIndicator());
    }

    // 表示範囲: 選択日のJST 5:00 〜 翌JST 5:00（UTCで表現）
    final rangeStartUtc = DateTime.utc(
      _selectedDate.year,
      _selectedDate.month,
      _selectedDate.day,
      _startHourJst - 9, // JST 5:00 = UTC 前日20:00（-4を渡すと日付繰り下げ）
    );
    final rangeEndUtc = rangeStartUtc.add(const Duration(hours: _totalHours));
    final totalHeight = _pxPerHour * _totalHours;

    // 現在時刻ライン（表示範囲内のみ）
    final nowUtc = DateTime.now().toUtc();
    final showNowLine =
        shouldShowNowLine(nowUtc: nowUtc, rangeStartUtc: rangeStartUtc, rangeEndUtc: rangeEndUtc);
    final nowY = showNowLine
        ? computeNowLineY(
            nowUtc: nowUtc, rangeStartUtc: rangeStartUtc, pxPerHour: _pxPerHour)
        : 0.0;

    return Column(
      children: [
        // 局名ヘッダー（横スクロールをボディと同期）
        SizedBox(
          height: _headerHeight,
          child: SingleChildScrollView(
            controller: _hScroll,
            scrollDirection: Axis.horizontal,
            child: Row(
              children: [
                const SizedBox(width: _timeColumnWidth),
                for (final station in stations)
                  SizedBox(
                    width: _stationColumnWidth,
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 4),
                      child: Center(
                        child: Text(
                          station.name,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          textAlign: TextAlign.center,
                          style: Theme.of(context).textTheme.labelMedium,
                        ),
                      ),
                    ),
                  ),
              ],
            ),
          ),
        ),
        const Divider(height: 1),
        Expanded(
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // 時間ラベル列（左固定、縦スクロールを局グリッドと同期）
              SizedBox(
                width: _timeColumnWidth,
                child: SingleChildScrollView(
                  controller: _vScroll,
                  child: _TimeAxisColumn(
                    totalHeight: totalHeight,
                    pxPerHour: _pxPerHour,
                  ),
                ),
              ),
              // 局グリッド（横スクロール、縦スクロールは時間ラベルと同期）
              Expanded(
                child: SingleChildScrollView(
                  controller: _hScroll,
                  scrollDirection: Axis.horizontal,
                  child: SingleChildScrollView(
                    controller: _vScroll,
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        for (final station in stations)
                          _StationColumn(
                            station: station,
                            programs: programsByStation[station] ?? [],
                            rangeStartUtc: rangeStartUtc,
                            rangeEndUtc: rangeEndUtc,
                            totalHeight: totalHeight,
                            pxPerHour: _pxPerHour,
                            showNowLine: showNowLine,
                            nowY: nowY,
                          ),
                      ],
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  bool _isSameDay(DateTime a, DateTime b) {
    return a.year == b.year && a.month == b.month && a.day == b.day;
  }

  String _formatDate(DateTime date) {
    final now = _jstToday;
    if (_isSameDay(date, now)) return '今日';
    if (_isSameDay(date, now.add(const Duration(days: 1)))) return '明日';
    return '${date.month}/${date.day}(${_weekday(date)})';
  }

  String _weekday(DateTime date) {
    const weekdays = ['月', '火', '水', '木', '金', '土', '日'];
    return weekdays[date.weekday - 1];
  }
}

/// 左端の時間ラベル列（5:00〜翌4:00）
class _TimeAxisColumn extends StatelessWidget {
  final double totalHeight;
  final double pxPerHour;

  const _TimeAxisColumn({
    required this.totalHeight,
    required this.pxPerHour,
  });

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: totalHeight,
      child: Column(
        children: [
          for (var h = 0; h < _totalHours; h++)
            SizedBox(
              height: pxPerHour,
              child: Align(
                alignment: Alignment.topRight,
                child: Padding(
                  padding: const EdgeInsets.only(top: 2, right: 6),
                  child: Text(
                    '${(_startHourJst + h) % 24}:00',
                    style: Theme.of(context).textTheme.labelSmall?.copyWith(
                          color: Theme.of(context).colorScheme.outline,
                        ),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

/// 1放送局分の時間グリッドカラム
class _StationColumn extends ConsumerWidget {
  final Station station;
  final List<Program> programs;
  final DateTime rangeStartUtc;
  final DateTime rangeEndUtc;
  final double totalHeight;
  final double pxPerHour;
  final bool showNowLine;
  final double nowY;

  const _StationColumn({
    required this.station,
    required this.programs,
    required this.rangeStartUtc,
    required this.rangeEndUtc,
    required this.totalHeight,
    required this.pxPerHour,
    required this.showNowLine,
    required this.nowY,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return SizedBox(
      width: _stationColumnWidth,
      height: totalHeight,
      child: Stack(
        clipBehavior: Clip.hardEdge,
        children: [
          // 1時間ごとの罫線
          for (var h = 0; h <= _totalHours; h++)
            Positioned(
              top: h * pxPerHour,
              left: 0,
              right: 0,
              child: Divider(
                height: 1,
                thickness: h % 6 == 0 ? 1.2 : 0.5,
                color: h % 6 == 0
                    ? Theme.of(context).colorScheme.outlineVariant
                    : Theme.of(context).colorScheme.surfaceContainerHighest,
              ),
            ),
          // 番組
          for (final program in programs)
            _positionedProgram(context, ref, program),
          // 現在時刻ライン
          if (showNowLine)
            Positioned(
              top: nowY,
              left: 0,
              right: 0,
              child: Container(
                height: 2,
                color: Colors.red,
              ),
            ),
        ],
      ),
    );
  }

  Widget _positionedProgram(
    BuildContext context,
    WidgetRef ref,
    Program program,
  ) {
    // 範囲内にクリップして位置・高さを計算
    final slot = computeProgramSlot(
      startTime: program.startTime,
      endTime: program.endTime,
      rangeStartUtc: rangeStartUtc,
      rangeEndUtc: rangeEndUtc,
      pxPerHour: pxPerHour,
      totalHeight: totalHeight,
    );
    if (!slot.visible) {
      return const SizedBox.shrink();
    }
    final top = slot.top;
    final height = slot.height;

    final isOnAir = program.isOnAir;
    final isTimefree = program.isTimefreeAvailable;
    final startJst = program.startTime.toUtc().add(jstOffset);
    final colorScheme = Theme.of(context).colorScheme;

    return Positioned(
      top: top,
      left: 1,
      right: 1,
      height: height,
      child: Material(
        color: isOnAir
            ? colorScheme.primaryContainer.withAlpha(120)
            : isTimefree
                ? colorScheme.secondaryContainer.withAlpha(90)
                : colorScheme.surfaceContainerLow,
        borderRadius: BorderRadius.circular(4),
        child: InkWell(
          borderRadius: BorderRadius.circular(4),
          onTap: () {
            final notifier = ref.read(playerProvider.notifier);
            if (program.isOnAir) {
              notifier.playLive(
                stationId: program.stationId,
                stationName: program.stationName,
                programTitle: program.title,
              );
            } else if (program.isTimefreeAvailable) {
              notifier.playTimefree(
                stationId: program.stationId,
                stationName: program.stationName,
                programTitle: program.title,
                startTime: program.startTime,
                endTime: program.endTime,
              );
            }
          },
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 2),
            decoration: BoxDecoration(
              border: Border.all(
                color: isOnAir ? colorScheme.primary : colorScheme.outline,
                width: isOnAir ? 1.5 : 0.5,
              ),
              borderRadius: BorderRadius.circular(4),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '${startJst.hour.toString().padLeft(2, '0')}:${startJst.minute.toString().padLeft(2, '0')}',
                  style: Theme.of(context).textTheme.labelSmall?.copyWith(
                        fontSize: 9,
                        color: colorScheme.outline,
                      ),
                ),
                Expanded(
                  child: Text(
                    program.title,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.labelSmall,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
