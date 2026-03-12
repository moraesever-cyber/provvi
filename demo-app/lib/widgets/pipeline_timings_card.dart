import 'package:flutter/material.dart';

import '../theme.dart';

class PipelineTimingsCard extends StatelessWidget {
  const PipelineTimingsCard({super.key, required this.session});

  final Map<String, dynamic> session;

  static const _labels = <String, String>{
    'integrity_check_ms':     'Play Integrity',
    'location_validation_ms': 'Localização',
    'camera_frame_ms':        'Câmera',
    'recapture_analysis_ms':  'Anti-recaptura',
    'jpeg_conversion_ms':     'JPEG',
    'c2pa_signing_ms':        'Assinatura C2PA',
    'backend_upload_ms':      'Upload backend',
    'total_ms':               'TOTAL',
  };

  @override
  Widget build(BuildContext context) {
    final raw = session['pipelineTimings'];
    if (raw == null || raw is! Map) return const SizedBox.shrink();

    final timings = Map<String, int>.fromEntries(
      raw.entries
          .where((e) => e.value is int)
          .map((e) => MapEntry(e.key as String, e.value as int)),
    );

    if (timings.isEmpty) return const SizedBox.shrink();

    return ExpansionTile(
      title: const Text('⏱ Tempos do pipeline'),
      initiallyExpanded: false,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
          child: Column(
            children: _labels.entries
                .where((e) => timings.containsKey(e.key))
                .map((e) {
                  final ms = timings[e.key]!;
                  final isTotal = e.key == 'total_ms';
                  final Color valueColor;
                  if (ms > 1000) {
                    valueColor = kRed;
                  } else if (ms <= 500) {
                    valueColor = kVerde;
                  } else {
                    valueColor = Colors.orange.shade800;
                  }
                  return Padding(
                    padding: const EdgeInsets.symmetric(vertical: 3),
                    child: Row(
                      children: [
                        SizedBox(
                          width: 140,
                          child: Text(
                            e.value,
                            style: TextStyle(
                              color: kCinzaT,
                              fontSize: 13,
                              fontWeight: isTotal
                                  ? FontWeight.bold
                                  : FontWeight.normal,
                            ),
                          ),
                        ),
                        Text(
                          '${ms}ms',
                          style: TextStyle(
                            color: valueColor,
                            fontSize: 13,
                            fontWeight: isTotal
                                ? FontWeight.bold
                                : FontWeight.w500,
                          ),
                        ),
                      ],
                    ),
                  );
                })
                .toList(),
          ),
        ),
      ],
    );
  }
}
