import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'ui/screens/live_screen.dart';
import 'ui/screens/program_guide_screen.dart';
import 'ui/screens/timefree_screen.dart';
import 'ui/screens/settings_screen.dart';
import 'ui/widgets/mini_player.dart';
import 'ui/theme/app_theme.dart';
import 'features/player/player_provider.dart';
import 'features/player/player_service.dart';
import 'features/theme/theme_provider.dart';

final _rootNavigatorKey = GlobalKey<NavigatorState>();
final _shellNavigatorKey = GlobalKey<NavigatorState>();

final routerProvider = Provider<GoRouter>((ref) {
  return GoRouter(
    navigatorKey: _rootNavigatorKey,
    initialLocation: '/',
    routes: [
      ShellRoute(
        navigatorKey: _shellNavigatorKey,
        builder: (context, state, child) {
          return _MainShell(child: child);
        },
        routes: [
          GoRoute(
            path: '/',
            pageBuilder: (context, state) => const NoTransitionPage(
              child: LiveScreen(),
            ),
          ),
          GoRoute(
            path: '/programs',
            pageBuilder: (context, state) => const NoTransitionPage(
              child: ProgramGuideScreen(),
            ),
          ),
          GoRoute(
            path: '/timefree',
            pageBuilder: (context, state) => const NoTransitionPage(
              child: TimefreeScreen(),
            ),
          ),
          GoRoute(
            path: '/settings',
            pageBuilder: (context, state) => const NoTransitionPage(
              child: SettingsScreen(),
            ),
          ),
        ],
      ),
      // ShellRoute外のルート（フルプレイヤー画面）
      GoRoute(
        parentNavigatorKey: _rootNavigatorKey,
        path: '/player',
        pageBuilder: (context, state) => const NoTransitionPage(
          child: FullPlayerScreen(),
        ),
      ),
    ],
  );
});

class _MainShell extends StatefulWidget {
  final Widget child;
  const _MainShell({required this.child});

  @override
  State<_MainShell> createState() => _MainShellState();
}

class _MainShellState extends State<_MainShell> {
  int _currentIndex = 0;

  void _onTabTapped(int index) {
    setState(() => _currentIndex = index);
    final locations = ['/', '/programs', '/timefree', '/settings'];
    GoRouter.of(context).go(locations[index]);
  }

  // GoRouter の遷移で _currentIndex を同期
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final routerState = GoRouterState.of(context);
    final locations = ['/', '/programs', '/timefree', '/settings'];
    final currentPath = routerState.uri.toString();
    final newIndex = locations.indexOf(currentPath);
    if (newIndex != -1 && newIndex != _currentIndex) {
      setState(() => _currentIndex = newIndex);
    }
  }

  @override
  Widget build(BuildContext context) {
    final brightness = Theme.of(context).brightness;
    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: SystemUiOverlayStyle(
        statusBarColor: Colors.transparent,
        statusBarIconBrightness:
            brightness == Brightness.dark ? Brightness.light : Brightness.dark,
        systemNavigationBarColor: Theme.of(context).scaffoldBackgroundColor,
        systemNavigationBarIconBrightness:
            brightness == Brightness.dark ? Brightness.light : Brightness.dark,
      ),
      child: SafeArea(
        child: Scaffold(
          body: Column(
            children: [
              Expanded(child: widget.child),
              const MiniPlayer(),
            ],
          ),
          bottomNavigationBar: BottomNavigationBar(
            currentIndex: _currentIndex,
            onTap: _onTabTapped,
            items: const [
              BottomNavigationBarItem(
                icon: Icon(Icons.radio),
                label: 'ライブ',
              ),
              BottomNavigationBarItem(
                icon: Icon(Icons.calendar_today),
                label: '番組表',
              ),
              BottomNavigationBarItem(
                icon: Icon(Icons.replay),
                label: 'タイムフリー',
              ),
              BottomNavigationBarItem(
                icon: Icon(Icons.settings),
                label: '設定',
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class RadikkApp extends ConsumerWidget {
  const RadikkApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final router = ref.watch(routerProvider);
    final themeMode = ref.watch(themeModeProvider);

    return MaterialApp.router(
      title: 'Radikk',
      debugShowCheckedModeBanner: false,
      theme: AppTheme.lightTheme,
      darkTheme: AppTheme.darkTheme,
      themeMode: themeMode,
      routerConfig: router,
    );
  }
}

/// フルプレイヤー画面
class FullPlayerScreen extends ConsumerWidget {
  const FullPlayerScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final playerState = ref.watch(playerProvider);
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(
        title: const Text('再生中'),
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: () => GoRouter.of(context).pop(),
        ),
      ),
      body: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          // 番組・放送局情報
          Card(
            margin: const EdgeInsets.all(24),
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                children: [
                  Text(
                    playerState.stationName ?? '放送局',
                    style: theme.textTheme.titleLarge,
                  ),
                  const SizedBox(height: 8),
                  if (playerState.programTitle != null)
                    Text(
                      playerState.programTitle!,
                      style: theme.textTheme.titleMedium,
                      textAlign: TextAlign.center,
                    ),
                ],
              ),
            ),
          ),
          // 再生コントロール
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              // シークバック10秒
              IconButton(
                icon: const Icon(Icons.replay_10),
                iconSize: 32,
                onPressed: () {
                  final newPosition = playerState.position - const Duration(seconds: 10);
                  ref.read(playerProvider.notifier)
                      .seek(newPosition.isNegative ? Duration.zero : newPosition);
                },
              ),
              const SizedBox(width: 16),
              // 再生/一時停止
              if (playerState.status == PlayerStatus.loading)
                const SizedBox(
                  width: 48,
                  height: 48,
                  child: CircularProgressIndicator(strokeWidth: 3),
                )
              else
                IconButton(
                  icon: Icon(
                    playerState.status == PlayerStatus.playing
                        ? Icons.pause_circle_filled
                        : Icons.play_circle_filled,
                    size: 48,
                    color: theme.colorScheme.primary,
                  ),
                  onPressed: () {
                    ref.read(playerProvider.notifier).togglePlayPause();
                  },
                ),
              const SizedBox(width: 16),
              // 停止
              IconButton(
                icon: const Icon(Icons.stop, size: 32),
                onPressed: () {
                  ref.read(playerProvider.notifier).stop();
                  GoRouter.of(context).pop();
                },
              ),
            ],
          ),
          const SizedBox(height: 24),
          // エラー表示
          if (playerState.errorMessage != null)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 32),
              child: Text(
                playerState.errorMessage!,
                style: TextStyle(color: Colors.red.shade700, fontSize: 13),
                textAlign: TextAlign.center,
              ),
            ),
          // シークバー
          if (playerState.duration != null)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 32),
              child: Column(
                children: [
                  Text(
                    '${_formatDuration(playerState.position)} / ${_formatDuration(playerState.duration!)}',
                    style: theme.textTheme.bodySmall,
                  ),
                  SliderTheme(
                    data: SliderTheme.of(context).copyWith(
                      trackHeight: 2,
                      overlayShape: const RoundSliderOverlayShape(
                          overlayRadius: 0),
                    ),
                    child: Slider(
                      value: playerState.position.inSeconds
                          .toDouble()
                          .clamp(0, playerState.duration!.inSeconds.toDouble()),
                      max: playerState.duration!.inSeconds.toDouble(),
                      // ドラッグ中の連続seekを避け、離した時点でシークする
                      onChanged: (_) {},
                      onChangeEnd: (value) {
                        ref.read(playerProvider.notifier)
                            .seek(Duration(seconds: value.toInt()));
                      },
                    ),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }

  String _formatDuration(Duration d) {
    final hours = d.inHours;
    final minutes = d.inMinutes.remainder(60);
    final seconds = d.inSeconds.remainder(60);
    if (hours > 0) {
      return '$hours:${minutes.toString().padLeft(2, '0')}:${seconds.toString().padLeft(2, '0')}';
    }
    return '$minutes:${seconds.toString().padLeft(2, '0')}';
  }
}
