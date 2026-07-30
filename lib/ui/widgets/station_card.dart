import 'package:flutter/material.dart';

class StationCard extends StatelessWidget {
  final String stationName;
  final String stationId;
  final String? programTitle;
  final DateTime? startTime;
  final DateTime? endTime;
  final VoidCallback onPlay;

  const StationCard({
    super.key,
    required this.stationName,
    required this.stationId,
    this.programTitle,
    this.startTime,
    this.endTime,
    required this.onPlay,
  });

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    stationName,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  const SizedBox(height: 4),
                  if (programTitle != null) ...[
                    Text(
                      programTitle!,
                      style: Theme.of(context).textTheme.bodyMedium,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                    ),
                    if (startTime != null && endTime != null)
                      Text(
                        '${_formatTime(startTime!)} 〜 ${_formatTime(endTime!)}',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                  ],
                  if (programTitle == null)
                    Text(
                      '放送休止中',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                ],
              ),
            ),
            const SizedBox(width: 12),
            ElevatedButton.icon(
              onPressed: onPlay,
              icon: const Icon(Icons.play_arrow, size: 20),
              label: const Text('聴く'),
            ),
          ],
        ),
      ),
    );
  }

  String _formatTime(DateTime dt) {
    return '${dt.hour.toString().padLeft(2, '0')}:${dt.minute.toString().padLeft(2, '0')}';
  }
}
