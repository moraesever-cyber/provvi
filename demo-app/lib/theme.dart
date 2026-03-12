import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';

const kNavy   = Color(0xFF07111F);
const kAzul   = Color(0xFF2563A8);
const kBlueL  = Color(0xFF4A9EE8);
const kCinza  = Color(0xFFF0F4F8);
const kCinzaT = Color(0xFF4A5568);
const kVerde  = Color(0xFF1A6B3C);
const kVerdeL = Color(0xFFE8F5EE);
const kRed    = Color(0xFFC0392B);
const kRedL   = Color(0xFFFFF0EE);

ThemeData buildTheme() {
  return ThemeData(
    colorScheme: ColorScheme.fromSeed(
      seedColor: kAzul,
      brightness: Brightness.light,
    ),
    useMaterial3: true,
    appBarTheme: AppBarTheme(
      backgroundColor: kNavy,
      foregroundColor: Colors.white,
      titleTextStyle: GoogleFonts.playfairDisplay(
        color: Colors.white,
        fontSize: 20,
        fontWeight: FontWeight.w700,
        letterSpacing: -0.2,
      ),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(backgroundColor: kAzul),
    ),
    cardTheme: const CardThemeData(
      elevation: 2,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.all(Radius.circular(12)),
      ),
    ),
    inputDecorationTheme: const InputDecorationTheme(
      border: OutlineInputBorder(),
      focusedBorder: OutlineInputBorder(
        borderSide: BorderSide(color: kAzul, width: 2),
      ),
    ),
  );
}
