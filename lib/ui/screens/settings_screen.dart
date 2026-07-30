import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../../features/auth/auth_provider.dart';
import '../../core/constants/area_map.dart';

class SettingsScreen extends ConsumerStatefulWidget {
  const SettingsScreen({super.key});

  @override
  ConsumerState<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends ConsumerState<SettingsScreen> {
  bool _backgroundPlayback = true;

  @override
  void initState() {
    super.initState();
    _loadSettings();
  }

  Future<void> _loadSettings() async {
    final prefs = await SharedPreferences.getInstance();
    setState(() {
      _backgroundPlayback = prefs.getBool('background_playback') ?? true;
    });
  }

  Future<void> _setBackgroundPlayback(bool value) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('background_playback', value);
    setState(() {
      _backgroundPlayback = value;
    });
  }

  @override
  Widget build(BuildContext context) {
    final selectedArea = ref.watch(selectedAreaProvider);

    return ListView(
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
                ref.read(selectedAreaProvider.notifier).state = value;
              }
            },
          ),
        ),
        const Divider(),

        _SectionTitle(title: '再生'),
        SwitchListTile(
          title: const Text('バックグラウンド再生'),
          subtitle: const Text('アプリを閉じても再生を継続します'),
          value: _backgroundPlayback,
          onChanged: _setBackgroundPlayback,
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
        const ListTile(
          leading: Icon(Icons.info_outline),
          title: Text('Radikk'),
          subtitle: Text('バージョン 0.0.1'),
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
