import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/constants/area_map.dart';
import '../../features/auth/auth_provider.dart';
import '../theme/app_theme.dart';

/// エリア選択ドロップダウン
class AreaSelector extends ConsumerWidget {
  const AreaSelector({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final selectedArea = ref.watch(selectedAreaProvider);

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Row(
        children: [
          const Icon(Icons.location_on,
              size: 18, color: AppTheme.primaryBlue),
          const SizedBox(width: 8),
          Expanded(
            child: DropdownButtonHideUnderline(
              child: DropdownButton<String>(
                value: selectedArea,
                isExpanded: true,
                style: Theme.of(context).textTheme.titleMedium,
                items: AreaMap.areas.map((area) {
                  return DropdownMenuItem(
                    value: area.id,
                    child: Text('${area.japanese} (${area.id})'),
                  );
                }).toList(),
                onChanged: (value) {
                  if (value != null) {
                    ref
                        .read(selectedAreaProvider.notifier)
                        .setSelectedArea(value);
                    // エリア変更時に再認証
                    ref.read(authStateProvider.notifier).authenticate();
                  }
                },
              ),
            ),
          ),
        ],
      ),
    );
  }
}
