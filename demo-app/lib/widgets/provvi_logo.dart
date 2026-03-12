import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';

import '../theme.dart';

/// Logo completo: símbolo orbital + wordmark "provvi" em Playfair Display.
///
/// [size] controla a altura do símbolo. O wordmark escala proporcionalmente.
/// [onDark] = true → variante bicolor (círculo branco + elipses kBlueL),
///            usada sobre fundos navy/escuros.
/// [onDark] = false → variante monocromática navy (#1B2A3E),
///            usada sobre fundos claros/brancos.
class ProvviLogo extends StatelessWidget {
  const ProvviLogo({
    super.key,
    this.size = 36,
    this.onDark = true,
  });

  final double size;
  final bool onDark;

  @override
  Widget build(BuildContext context) {
    final circleColor  = onDark ? Colors.white       : const Color(0xFF1B2A3E);
    final ellipseColor = onDark ? kBlueL             : const Color(0xFF1B2A3E);
    final textColor    = onDark ? Colors.white       : const Color(0xFF1B2A3E);

    return Row(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.center,
      children: [
        // Nudge óptico: símbolo desce ~8% do seu tamanho para equilibrar
        // visualmente com o descendente do "p" no wordmark.
        // Equivalente ao translateY(4px) aplicado ao SVG no logo-sheet.html.
        Transform.translate(
          offset: Offset(0, size * 0.08),
          child: CustomPaint(
            size: Size(size, size),
            painter: _SymbolPainter(
              circleColor: circleColor,
              ellipseColor: ellipseColor,
            ),
          ),
        ),
        SizedBox(width: size * 0.28),
        Text(
          'provvi',
          style: GoogleFonts.playfairDisplay(
            fontWeight: FontWeight.w700,
            fontSize: size * 0.88,
            color: textColor,
            letterSpacing: -0.01 * size,
            height: 1,
          ),
        ),
      ],
    );
  }
}

/// Apenas o símbolo orbital, sem wordmark.
class ProvviMark extends StatelessWidget {
  const ProvviMark({super.key, this.size = 36, this.onDark = true});

  final double size;
  final bool onDark;

  @override
  Widget build(BuildContext context) {
    return CustomPaint(
      size: Size(size, size),
      painter: _SymbolPainter(
        circleColor:  onDark ? Colors.white : const Color(0xFF1B2A3E),
        ellipseColor: onDark ? kBlueL       : const Color(0xFF1B2A3E),
      ),
    );
  }
}

// ---------------------------------------------------------------------------

class _SymbolPainter extends CustomPainter {
  const _SymbolPainter({
    required this.circleColor,
    required this.ellipseColor,
  });

  final Color circleColor;
  final Color ellipseColor;

  // ViewBox original: 48×48
  static const double _vb = 48;

  @override
  void paint(Canvas canvas, Size size) {
    final scale = size.width / _vb;
    canvas.scale(scale, scale);

    final circlePaint = Paint()
      ..color = circleColor
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2.5
      ..isAntiAlias = true;

    final ellipsePaint = Paint()
      ..color = ellipseColor
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2.0
      ..isAntiAlias = true;

    final dotPaint = Paint()
      ..color = ellipseColor
      ..style = PaintingStyle.fill
      ..isAntiAlias = true;

    const cx = 24.0;
    const cy = 24.0;

    // Círculo externo
    canvas.drawCircle(const Offset(cx, cy), 19, circlePaint);

    // 3 elipses rotacionadas 0°, 60°, 120°
    const rect = Rect.fromLTWH(cx - 7.5, cy - 15, 15, 30);
    for (final deg in [0.0, 60.0, 120.0]) {
      canvas.save();
      canvas.translate(cx, cy);
      canvas.rotate(deg * math.pi / 180);
      canvas.translate(-cx, -cy);
      canvas.drawOval(rect, ellipsePaint);
      canvas.restore();
    }

    // Ponto central
    canvas.drawCircle(const Offset(cx, cy), 3, dotPaint);
  }

  @override
  bool shouldRepaint(_SymbolPainter old) =>
      old.circleColor != circleColor || old.ellipseColor != ellipseColor;
}
