import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/models/auth_token.dart';
import 'auth_service.dart';

/// 選択中のエリアID
final selectedAreaProvider = StateProvider<String>((ref) => 'JP13');

/// AuthService インスタンス
final authServiceProvider = Provider<AuthService>((ref) => AuthService());

/// 認証状態を管理するProvider
final authStateProvider = AsyncNotifierProvider<AuthNotifier, AuthToken?>(
  AuthNotifier.new,
);

class AuthNotifier extends AsyncNotifier<AuthToken?> {
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

  /// 認証実行
  /// 認証の完了前にエリアが変更された場合は、最新のエリアで再認証する
  Future<void> authenticate() async {
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
