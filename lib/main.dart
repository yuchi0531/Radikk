import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'app.dart';
import 'features/auth/auth_provider.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await SelectedArea.restore();
  runApp(
    const ProviderScope(
      child: RadikkApp(),
    ),
  );
}
