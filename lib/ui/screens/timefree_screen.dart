import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/utils/radiko_time.dart';
import '../../features/auth/auth_provider.dart';
import '../../features/player/player_provider.dart';
import '../../features/timefree/timefree_repository.dart';
import '../widgets/area_selector.dart';
import '../widgets/program_tile.dart';

class TimefreeScreen extends ConsumerStatefulWidget {
  const TimefreeScreen({super.key});

  @override
  ConsumerState<TimefreeScreen> createState() => _TimefreeScreenState();
}

class _TimefreeScreenState extends ConsumerState<TimefreeScreen> {
  DateTime _selectedDate = DateTime.now();

  @override
  void initState() {
    super.initState();
    // タイムフリーは放送終了後7日以内のため、デフォルトはJST基準の昨日
    _selectedDate = _jstYesterday;
  }

  /// JST基準の「昨日」（タイムフリーは放送終了後7日以内のため、デフォルトは前日）
  DateTime get _jstYesterday {
    final jstNow = DateTime.now().toUtc().add(jstOffset);
    final today = DateTime(jstNow.year, jstNow.month, jstNow.day);
    return today.subtract(const Duration(days: 1));
  }

  @override
  Widget build(BuildContext context) {
    final areaId = ref.watch(selectedAreaProvider);
    final timefreeAsync = ref.watch(
      timefreeAllStationsProvider((areaId, _selectedDate)),
    );

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
              // JST基準の今日（0時）を起点に、7日前〜昨日のチップを生成
              final jstToday = _jstYesterday.add(const Duration(days: 1));
              final date = jstToday.subtract(Duration(days: 7 - index));
              final isSelected = _isSameDay(date, _selectedDate);
              return Padding(
                padding: const EdgeInsets.symmetric(horizontal: 4),
                child: ChoiceChip(
                  label: Text(
                    isSelected ? _formatDate(date) : _formatDateShort(date),
                  ),
                  selected: isSelected,
                  onSelected: (_) => setState(() => _selectedDate = date),
                )
              );
            },
          ),
        ),
        const Divider(height: 1),
        Expanded(
          child: timefreeAsync.when(
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (e, _) => Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  const Text('読み込みに失敗しました'),
                  const SizedBox(height: 8),
                  ElevatedButton(
                    onPressed: () => ref.invalidate(
                      timefreeAllStationsProvider((areaId, _selectedDate)),
                    ),
                    child: const Text('再試行'),
                  ),
                ],
              ),
            ),
            data: (programs) {
              if (programs.isEmpty) {
                return const Center(
                  child: Text('タイムフリーで聴ける番組はありません'),
                );
              }
              return ListView.builder(
                padding: const EdgeInsets.only(bottom: 80),
                itemCount: programs.length,
                itemBuilder: (context, index) {
                  final (program, stationName) = programs[index];
                  return ProgramTile(
                    program: program,
                    stationName: stationName,
                    onPlay: () {
                      ref.read(playerProvider.notifier).playTimefree(
                            stationId: program.stationId,
                            stationName: stationName,
                            programTitle: program.title,
                            startTime: program.startTime,
                            endTime: program.endTime,
                          );
                    },
                  );
                },
              );
            },
          ),
        ),
      ],
    );
  }

  bool _isSameDay(DateTime a, DateTime b) {
    return a.year == b.year && a.month == b.month && a.day == b.day;
  }

  String _formatDate(DateTime date) {
    return '${date.month}/${date.day}(${_weekday(date)})';
  }

  String _formatDateShort(DateTime date) {
    return '${date.month}/${date.day}';
  }

  String _weekday(DateTime date) {
    const w = ['月', '火', '水', '木', '金', '土', '日'];
    return w[date.weekday - 1];
  }
}
