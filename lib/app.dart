import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'ui/screens/live_screen.dart';
import 'ui/screens/program_guide_screen.dart';
import 'ui/screens/timefree_screen.dart';
import 'ui/screens/settings_screen.dart';
import 'ui/widgets/mini_player.dart';
import 'ui/theme/app_theme.dart';

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

  @override
  Widget build(BuildContext context) {
    return Scaffold(
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
    );
  }
}

class RadikkApp extends ConsumerWidget {
  const RadikkApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final router = ref.watch(routerProvider);

    return MaterialApp.router(
      title: 'Radikk',
      debugShowCheckedModeBanner: false,
      theme: AppTheme.lightTheme,
      routerConfig: router,
    );
  }
}
