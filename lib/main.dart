import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'app_state.dart';
import 'pages/home_page.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(
    ChangeNotifierProvider(
      create: (_) => AppState(),
      child: const AutoClickerApp(),
    ),
  );
}

class AutoClickerApp extends StatelessWidget {
  const AutoClickerApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '自动点击器',
      theme: ThemeData(
        useMaterial3: true,
        colorSchemeSeed: const Color(0xFF2196F3),
      ),
      darkTheme: ThemeData(
        useMaterial3: true,
        brightness: Brightness.dark,
        colorSchemeSeed: const Color(0xFF2196F3),
      ),
      home: const HomePage(),
      debugShowCheckedModeBanner: false,
    );
  }
}
