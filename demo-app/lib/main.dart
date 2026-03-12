import 'package:flutter/material.dart';

import 'screens/capture_screen.dart';
import 'screens/identification_screen.dart';
import 'screens/result_screen.dart';
import 'screens/tamper_screen.dart';
import 'theme.dart';

void main() {
  runApp(const ProvviDemoApp());
}

class ProvviDemoApp extends StatelessWidget {
  const ProvviDemoApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Provvi Demo',
      debugShowCheckedModeBanner: false,
      theme: buildTheme(),
      initialRoute: '/',
      routes: {
        '/':        (_) => const IdentificationScreen(),
        '/capture': (_) => const CaptureScreen(),
        '/result':  (_) => const ResultScreen(),
        '/tamper':  (_) => const TamperScreen(),
      },
    );
  }
}
