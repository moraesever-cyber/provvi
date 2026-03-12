import 'package:flutter/material.dart';

import '../theme.dart';

class AuthBadge extends StatelessWidget {
  const AuthBadge({super.key, required this.valid, required this.label});

  final bool valid;
  final String label;

  @override
  Widget build(BuildContext context) {
    final bg    = valid ? kVerdeL : kRedL;
    final color = valid ? kVerde  : kRed;
    final icon  = valid ? Icons.check_circle : Icons.cancel;

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: bg,
        borderRadius: BorderRadius.circular(6),
        border: Border.all(color: color),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 14, color: color),
          const SizedBox(width: 4),
          Text(
            label,
            style: TextStyle(
              color: color,
              fontSize: 12,
              fontWeight: FontWeight.w600,
            ),
          ),
        ],
      ),
    );
  }
}
