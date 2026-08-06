import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:package_info_plus/package_info_plus.dart';
import '../../features/auth/auth_provider.dart';
import '../../features/player/player_provider.dart';
import '../../features/theme/theme_provider.dart';
import '../../core/constants/area_map.dart';

class SettingsScreen extends ConsumerStatefulWidget {
  const SettingsScreen({super.key});

  @override
  ConsumerState<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends ConsumerState<SettingsScreen> {
  String _version = '0.0.1';

  @override
  void initState() {
    super.initState();
    _loadVersion();
  }

  Future<void> _loadVersion() async {
    final info = await PackageInfo.fromPlatform();
    if (!mounted) return;
    setState(() {
      _version = info.version;
    });
  }

  @override
  Widget build(BuildContext context) {
    final selectedArea = ref.watch(selectedAreaProvider);
    final themeMode = ref.watch(themeModeProvider);

    return ListView(
      padding: const EdgeInsets.only(bottom: 80),
      children: [
        const SizedBox(height: 16),
        _SectionTitle(title: 'デフォルトエリア'),
        ListTile(
          leading: const Icon(Icons.location_on),
          title: const Text('エリア'),
          trailing: DropdownButton<String>(
            value: selectedArea,
            underline: const SizedBox(),
            items: AreaMap.areas.map((a) {
              return DropdownMenuItem(
                value: a.id,
                child: Text(a.japanese),
              );
            }).toList(),
            onChanged: (value) {
              if (value != null) {
                final current = ref.read(selectedAreaProvider);
                if (current != value) {
                  ref
                      .read(selectedAreaProvider.notifier)
                      .setSelectedArea(value);
                  // エリア変更時は新しいエリアで再認証する
                  // （古いエリアのトークンのままだと403になるため）
                  ref.read(authStateProvider.notifier).authenticate();
                }
              }
            },
          ),
        ),
        const Divider(),

        _SectionTitle(title: '表示'),
        ListTile(
          leading: const Icon(Icons.brightness_6),
          title: const Text('テーマ'),
          trailing: SegmentedButton<ThemeMode>(
            segments: const [
              ButtonSegment(
                value: ThemeMode.system,
                label: Text('自動'),
              ),
              ButtonSegment(
                value: ThemeMode.light,
                icon: Icon(Icons.light_mode, size: 16),
              ),
              ButtonSegment(
                value: ThemeMode.dark,
                icon: Icon(Icons.dark_mode, size: 16),
              ),
            ],
            selected: {themeMode},
            onSelectionChanged: (mode) {
              ref.read(themeModeProvider.notifier).setThemeMode(mode.first);
            },
          ),
        ),
        const Divider(),

        _SectionTitle(title: '再生'),
        SwitchListTile(
          title: const Text('バックグラウンド再生'),
          subtitle: const Text('アプリを閉じても再生を継続します'),
          value: ref.watch(backgroundPlaybackProvider),
          onChanged: (value) =>
              ref.read(backgroundPlaybackProvider.notifier).setEnabled(value),
        ),
        const Divider(),

        _SectionTitle(title: 'データ'),
        ListTile(
          leading: const Icon(Icons.delete_outline),
          title: const Text('認証キャッシュを削除'),
          subtitle: const Text('トークンとエリア情報をクリアします'),
          onTap: () async {
            await ref.read(authStateProvider.notifier).clearAuth();
            if (context.mounted) {
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('キャッシュを削除しました')),
              );
            }
          },
        ),
        const Divider(),

        _SectionTitle(title: 'アプリ情報'),
        ListTile(
          leading: const Icon(Icons.info_outline),
          title: const Text('Radikk'),
          subtitle: Text('バージョン $_version'),
        ),
        const ListTile(
          leading: Icon(Icons.code),
          title: Text('ライセンス'),
          subtitle: Text('MIT License'),
        ),
      ],
    );
  }
}

class _SectionTitle extends StatelessWidget {
  final String title;
  const _SectionTitle({required this.title});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 4),
      child: Text(
        title,
        style: TextStyle(
          fontSize: 12,
          fontWeight: FontWeight.bold,
          color: Theme.of(context).colorScheme.primary,
        ),
      ),
    );
  }
}
