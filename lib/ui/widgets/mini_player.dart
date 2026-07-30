import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../features/player/player_provider.dart';
import '../../features/player/player_service.dart';

/// 画面下部に固定表示されるミニプレイヤー
class MiniPlayer extends ConsumerWidget {
  const MiniPlayer({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final playerState = ref.watch(playerProvider);

    // 再生中でなければ非表示
    if (playerState.status == PlayerStatus.idle ||
        playerState.status == PlayerStatus.stopped) {
      return const SizedBox.shrink();
    }

    final isPlaying = playerState.status == PlayerStatus.playing;
    final isLoading = playerState.status == PlayerStatus.loading;
    final isError = playerState.status == PlayerStatus.error;

    return Container(
      decoration: BoxDecoration(
        color: isError ? Colors.red.shade50 : Colors.white,
        boxShadow: [
          BoxShadow(
            color: Colors.black.withAlpha(26),
            blurRadius: 4,
            offset: const Offset(0, -2),
          ),
        ],
      ),
      child: SafeArea(
        child: Material(
          color: isError ? Colors.red.shade50 : Colors.white,
          child: InkWell(
            onTap: () {
              Navigator.of(context).pushNamed('/player');
            },
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              child: isError
                  ? Row(
                      children: [
                        Expanded(
                          child: Text(
                            playerState.errorMessage ?? '再生エラー',
                            style: TextStyle(
                              color: Colors.red.shade700,
                              fontSize: 13,
                            ),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                        const SizedBox(width: 8),
                        IconButton(
                          icon: const Icon(Icons.close, size: 20),
                          onPressed: () {
                            ref.read(playerProvider.notifier).stop();
                          },
                        ),
                      ],
                    )
                  : Row(
                children: [
                  // 放送局・番組情報
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(
                          playerState.stationName ?? '放送局',
                          style: Theme.of(context).textTheme.titleMedium,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                        if (playerState.programTitle != null)
                          Text(
                            playerState.programTitle!,
                            style: Theme.of(context).textTheme.bodySmall,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                      ],
                    ),
                  ),
                  const SizedBox(width: 8),
                  // 再生/一時停止ボタン
                  if (isLoading)
                    const SizedBox(
                      width: 36,
                      height: 36,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  else
                    IconButton(
                      icon: Icon(
                        isPlaying ? Icons.pause : Icons.play_arrow,
                        color: Theme.of(context).colorScheme.primary,
                      ),
                      onPressed: () {
                        ref.read(playerProvider.notifier).togglePlayPause();
                      },
                    ),
                  // 停止ボタン
                  IconButton(
                    icon: const Icon(Icons.close, size: 20),
                    onPressed: () {
                      ref.read(playerProvider.notifier).stop();
                    },
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
