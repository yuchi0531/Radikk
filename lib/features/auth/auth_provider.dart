import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../../core/models/auth_token.dart';
import 'auth_service.dart';

/// 選択中のエリアIDを管理するNotifier（SharedPreferencesに永続化）
class SelectedArea extends Notifier<String> {
  static const _key = 'selected_area';
  static String _cached = 'JP13'; // 起動時に読み込んだ値を保持

  @override
  String build() => _cached;

  Future<void> setSelectedArea(String value) async {
    _cached = value;
    state = value;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_key, value);
  }

  /// 起動時（main()）に呼び出して保存されたエリアを復元する
  static Future<void> restore() async {
    final prefs = await SharedPreferences.getInstance();
    _cached = prefs.getString(_key) ?? 'JP13';
  }
}

/// 選択中のエリアID
final selectedAreaProvider = NotifierProvider<SelectedArea, String>(
  SelectedArea.new,
);

/// AuthService インスタンス
final authServiceProvider = Provider<AuthService>((ref) => AuthService());

/// 認証状態を管理するProvider
final authStateProvider = AsyncNotifierProvider<AuthNotifier, AuthToken?>(
  AuthNotifier.new,
);

class AuthNotifier extends AsyncNotifier<AuthToken?> {
  /// 実行中の認証（単一フライト化）
  Future<void>? _inFlight;

  @override
  Future<AuthToken?> build() async {
    // 起動時にキャッシュされたトークンを復元
    // （期限切れならnullとして扱う）
    final service = ref.read(authServiceProvider);
    final cached = await service.loadCachedToken();
    if (cached != null && !cached.isExpired) {
      return cached;
    }
    return null;
  }

  /// 認証実行（同時呼び出しは単一フライトに集約）
  /// 認証の完了前にエリアが変更された場合は、最新のエリアで再認証する
  Future<void> authenticate() {
    return _inFlight ??= _doAuthenticate().whenComplete(() => _inFlight = null);
  }

  Future<void> _doAuthenticate() async {
    state = const AsyncLoading();
    final service = ref.read(authServiceProvider);

    state = await AsyncValue.guard(() async {
      while (true) {
        final areaId = ref.read(selectedAreaProvider);
        final token = await service.authenticate(areaId);
        // 認証中にエリアが変わった場合は古いエリアのトークンを破棄して再認証
        if (ref.read(selectedAreaProvider) == areaId) {
          return token;
        }
      }
    });
  }

  /// 認証クリア
  Future<void> clearAuth() async {
    final service = ref.read(authServiceProvider);
    await service.clearToken();
    state = const AsyncData(null);
  }
}
