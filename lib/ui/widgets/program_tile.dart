import 'package:flutter/material.dart';
import '../../core/models/program.dart';

class ProgramTile extends StatelessWidget {
  final Program program;
  final String stationName;
  final VoidCallback onPlay;

  const ProgramTile({
    super.key,
    required this.program,
    required this.stationName,
    required this.onPlay,
  });

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // 時間表示
            SizedBox(
              width: 56,
              child: Column(
                children: [
                  Text(
                    _formatTime(program.startTime),
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  const Text('〜'),
                  Text(
                    _formatTime(program.endTime),
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ],
              ),
            ),
            const SizedBox(width: 12),
            // 番組情報
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    program.title,
                    style: Theme.of(context).textTheme.titleMedium,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                  ),
                  const SizedBox(height: 2),
                  Text(
                    stationName,
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                  if (program.personality != null) ...[
                    const SizedBox(height: 2),
                    Text(
                      program.personality!,
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ],
                ],
              ),
            ),
            // 再生ボタン
            IconButton(
              icon: const Icon(Icons.play_circle_fill, size: 36),
              color: Theme.of(context).colorScheme.primary,
              onPressed: onPlay,
            ),
          ],
        ),
      ),
    );
  }

  String _formatTime(DateTime dt) {
    // startTime/endTime は UTC で保持されているため、ローカル時刻で表示する
    final local = dt.toLocal();
    return '${local.hour.toString().padLeft(2, '0')}:${local.minute.toString().padLeft(2, '0')}';
  }
}
