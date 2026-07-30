import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../features/auth/auth_provider.dart';
import '../../features/player/player_provider.dart';
import '../../features/player/player_service.dart';
import '../../features/programs/program_repository.dart';
import '../widgets/area_selector.dart';
import '../widgets/station_card.dart';

class LiveScreen extends ConsumerWidget {
  const LiveScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final areaId = ref.watch(selectedAreaProvider);
    final nowPlayingAsync = ref.watch(nowPlayingProvider(areaId));
    final playerState = ref.watch(playerProvider);

    return Column(
      children: [
        const AreaSelector(),
        // 再生エラー表示
        if (playerState.status == PlayerStatus.error &&
            playerState.errorMessage != null)
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(12),
            color: Colors.red.shade50,
            child: Row(
              children: [
                const Icon(Icons.error_outline, color: Colors.red, size: 20),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    playerState.errorMessage!,
                    style: TextStyle(color: Colors.red.shade700, fontSize: 13),
                  ),
                ),
                IconButton(
                  icon: const Icon(Icons.close, size: 16),
                  onPressed: () => ref.read(playerProvider.notifier).stop(),
                  padding: EdgeInsets.zero,
                  constraints: const BoxConstraints(),
                ),
              ],
            ),
          ),
        Expanded(
          child: nowPlayingAsync.when(
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (error, _) => Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  const Text('読み込みに失敗しました'),
                  const SizedBox(height: 8),
                  Text(
                    error.toString(),
                    style: Theme.of(context).textTheme.bodySmall,
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 16),
                  ElevatedButton(
                    onPressed: () => ref.invalidate(nowPlayingProvider(areaId)),
                    child: const Text('再試行'),
                  ),
                ],
              ),
            ),
            data: (stations) {
              if (stations.isEmpty) {
                return const Center(child: Text('放送局が見つかりません'));
              }
              return ListView.builder(
                padding: const EdgeInsets.only(bottom: 80),
                itemCount: stations.length,
                itemBuilder: (context, index) {
                  final (station, program) = stations[index];
                  return StationCard(
                    stationName: station.name,
                    stationId: station.id,
                    programTitle: program?.title,
                    startTime: program?.startTime,
                    endTime: program?.endTime,
                    onPlay: () {
                      _playStation(context, ref, station.id, station.name,
                          program?.title);
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

  Future<void> _playStation(BuildContext context, WidgetRef ref,
      String stationId, String stationName, String? programTitle) async {
    // 認証が必要な場合は認証を実行
    final authState = ref.read(authStateProvider);
    if (authState.valueOrNull == null) {
      await ref.read(authStateProvider.notifier).authenticate();

      // 認証成功を確認
      final authState2 = ref.read(authStateProvider);
      if (authState2.valueOrNull == null) {
        if (context.mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('認証に失敗しました')),
          );
        }
        return;
      }
    }

    // 再生開始
    ref.read(playerProvider.notifier).playLive(
          stationId: stationId,
          stationName: stationName,
          programTitle: programTitle,
        );
  }
}
