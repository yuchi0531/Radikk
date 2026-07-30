import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../features/auth/auth_provider.dart';
import '../../features/programs/program_repository.dart';
import '../../features/stations/station_repository.dart';
import '../widgets/area_selector.dart';

class ProgramGuideScreen extends ConsumerStatefulWidget {
  const ProgramGuideScreen({super.key});

  @override
  ConsumerState<ProgramGuideScreen> createState() => _ProgramGuideScreenState();
}

class _ProgramGuideScreenState extends ConsumerState<ProgramGuideScreen> {
  DateTime _selectedDate = DateTime.now();

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
              final date = DateTime.now().add(Duration(days: index));
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
              return ListView.builder(
                padding: const EdgeInsets.only(bottom: 80),
                itemCount: stations.length,
                itemBuilder: (context, index) {
                  final station = stations[index];
                  return _StationProgramList(
                    stationId: station.id,
                    stationName: station.name,
                    date: _selectedDate,
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
    final now = DateTime.now();
    if (_isSameDay(date, now)) return '今日';
    if (_isSameDay(date, now.add(const Duration(days: 1)))) return '明日';
    return '${date.month}/${date.day}(${_weekday(date)})';
  }

  String _weekday(DateTime date) {
    const weekdays = ['月', '火', '水', '木', '金', '土', '日'];
    return weekdays[date.weekday - 1];
  }
}

class _StationProgramList extends ConsumerWidget {
  final String stationId;
  final String stationName;
  final DateTime date;

  const _StationProgramList({
    required this.stationId,
    required this.stationName,
    required this.date,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final programsAsync = ref.watch(
      dailyProgramsProvider((stationId, date)),
    );

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
          child: Text(
            stationName,
            style: Theme.of(context).textTheme.titleMedium,
          ),
        ),
        programsAsync.when(
          loading: () => const Padding(
            padding: EdgeInsets.all(8),
            child: SizedBox(
              height: 20,
              width: 20,
              child: CircularProgressIndicator(strokeWidth: 2),
            ),
          ),
          error: (e, _) => Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Text('取得失敗', style: Theme.of(context).textTheme.bodySmall),
          ),
          data: (programs) {
            if (programs.isEmpty) {
              return const Padding(
                padding: EdgeInsets.symmetric(horizontal: 16),
                child: Text('番組情報なし'),
              );
            }
            return SizedBox(
              height: 64,
              child: ListView.builder(
                scrollDirection: Axis.horizontal,
                padding: const EdgeInsets.symmetric(horizontal: 12),
                itemCount: programs.length,
                itemBuilder: (context, index) {
                  final p = programs[index];
                  final isOnAir = p.isOnAir;
                  return Container(
                    width: 160,
                    margin: const EdgeInsets.symmetric(horizontal: 4),
                    padding: const EdgeInsets.all(8),
                    decoration: BoxDecoration(
                      color: isOnAir ? Colors.blue.shade50 : Colors.white,
                      borderRadius: BorderRadius.circular(4),
                      border: Border.all(
                        color: isOnAir
                            ? Colors.blue.shade200
                            : Colors.grey.shade300,
                      ),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Text(
                          '${p.startTime.hour.toString().padLeft(2, '0')}:${p.startTime.minute.toString().padLeft(2, '0')}〜',
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                        Text(
                          p.title,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: Theme.of(context).textTheme.bodyMedium,
                        ),
                      ],
                    ),
                  );
                },
              ),
            );
          },
        ),
        const Divider(height: 1),
      ],
    );
  }
}
