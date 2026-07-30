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
    return null; // 初回は明示的にauthenticateを呼ぶ
  }

  /// 認証実行
  Future<void> authenticate() async {
    state = const AsyncLoading();
    final areaId = ref.read(selectedAreaProvider);
    final service = ref.read(authServiceProvider);

    state = await AsyncValue.guard(() async {
      return service.authenticate(areaId);
    });
  }

  /// 認証クリア
  Future<void> clearAuth() async {
    final service = ref.read(authServiceProvider);
    await service.clearToken();
    state = const AsyncData(null);
  }
}
