import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'package:url_launcher/url_launcher.dart';

import '../theme.dart';
import '../widgets/pipeline_timings_card.dart';

class ResultScreen extends StatelessWidget {
  const ResultScreen({super.key});

  static const _verifierBase =
      'https://ldp4x25jnk2mrppz75ts2kiq7a0bylxw.lambda-url.sa-east-1.on.aws/';

  @override
  Widget build(BuildContext context) {
    final args =
        ModalRoute.of(context)!.settings.arguments as Map<String, dynamic>;
    final session     = args['session']     as Map<String, dynamic>;
    final capturedBy  = args['capturedBy']  as String;
    final referenceId = args['referenceId'] as String;

    final sessionId          = session['sessionId']          as String? ?? '';
    final frameHashHex       = session['frameHashHex']       as String? ?? '';
    final locationSuspicious = session['locationSuspicious'] as bool?   ?? false;
    final clockSuspicious    = session['clockSuspicious']    as bool?   ?? false;
    final hasIntegrityToken  = session['hasIntegrityToken']  as bool?   ?? false;
    final capturedAtRaw  = session['capturedAtMs'];
    final capturedAtMs = capturedAtRaw is int
        ? capturedAtRaw
        : (capturedAtRaw as num?)?.toInt() ?? 0;
    final manifestJson    = session['manifestJson']    as String? ?? '';
    final integrityRisk   = session['integrityRisk']   as String? ?? 'NONE';

    final capturedAt = DateTime.fromMillisecondsSinceEpoch(capturedAtMs);
    final dataFormatada =
        '${capturedAt.day.toString().padLeft(2, '0')}/'
        '${capturedAt.month.toString().padLeft(2, '0')}/'
        '${capturedAt.year} '
        '${capturedAt.hour.toString().padLeft(2, '0')}:'
        '${capturedAt.minute.toString().padLeft(2, '0')}:'
        '${capturedAt.second.toString().padLeft(2, '0')}';

    final verifierUrl = '$_verifierBase?session_id=$sessionId';

    return Scaffold(
      appBar: AppBar(
        title: const Text('Captura Assinada'),
        automaticallyImplyLeading: false,
      ),
      body: Column(
        children: [
          Expanded(
            child: SingleChildScrollView(
              padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  _AuthHeader(),
                  if (integrityRisk == 'MEDIUM') ...[
                    const SizedBox(height: 12),
                    _IntegrityRiskBanner(),
                  ],
                  const SizedBox(height: 16),
                  _MetadataGrid(
                    capturedBy: capturedBy,
                    referenceId: referenceId,
                    dataFormatada: dataFormatada,
                    locationSuspicious: locationSuspicious,
                    clockSuspicious: clockSuspicious,
                    hasIntegrityToken: hasIntegrityToken,
                    integrityRisk: integrityRisk,
                  ),
                  const SizedBox(height: 16),
                  _HashCard(
                    hash: frameHashHex,
                    onCopy: () {
                      Clipboard.setData(ClipboardData(text: frameHashHex));
                      ScaffoldMessenger.of(context).showSnackBar(
                        const SnackBar(
                          content: Text('Hash copiado'),
                          behavior: SnackBarBehavior.floating,
                          duration: Duration(seconds: 2),
                        ),
                      );
                    },
                  ),
                  const SizedBox(height: 16),
                  _QrCard(url: verifierUrl),
                  const SizedBox(height: 16),
                  Card(child: PipelineTimingsCard(session: session)),
                  const SizedBox(height: 8),
                ],
              ),
            ),
          ),
          _ActionButtons(
            verifierUrl: verifierUrl,
            manifestJson: manifestJson,
            session: session,
            capturedBy: capturedBy,
          ),
        ],
      ),
    );
  }
}

class _AuthHeader extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: kVerdeL,
        borderRadius: BorderRadius.circular(12),
        border: const Border(left: BorderSide(color: kVerde, width: 4)),
      ),
      child: const Row(
        children: [
          Icon(Icons.verified, color: kVerde, size: 32),
          SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Manifesto C2PA Registrado',
                  style: TextStyle(
                    color: kVerde,
                    fontSize: 18,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                Text(
                  'Assinatura C2PA aplicada · ICP-Brasil',
                  style: TextStyle(color: kVerde, fontSize: 12),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _IntegrityRiskBanner extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: const Color(0xFFFFF3CD),
        borderRadius: BorderRadius.circular(12),
        border: const Border(
          left: BorderSide(color: Color(0xFFF59E0B), width: 4),
        ),
      ),
      child: const Row(
        children: [
          Icon(Icons.warning_amber_rounded,
              color: Color(0xFFB45309), size: 32),
          SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Risco Médio — Sessão Flagada',
                  style: TextStyle(
                    color: Color(0xFF92400E),
                    fontSize: 15,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                Text(
                  'Indicadores de suspeita registrados no manifesto C2PA. '
                  'Disponível para revisão da seguradora.',
                  style: TextStyle(
                      color: Color(0xFF92400E), fontSize: 12),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _MetadataGrid extends StatelessWidget {
  const _MetadataGrid({
    required this.capturedBy,
    required this.referenceId,
    required this.dataFormatada,
    required this.locationSuspicious,
    required this.clockSuspicious,
    required this.hasIntegrityToken,
    required this.integrityRisk,
  });

  final String capturedBy;
  final String referenceId;
  final String dataFormatada;
  final bool locationSuspicious;
  final bool clockSuspicious;
  final bool hasIntegrityToken;
  final String integrityRisk;

  @override
  Widget build(BuildContext context) {
    final riskLabel = switch (integrityRisk) {
      'MEDIUM' => '⚠️ Médio',
      'HIGH'   => '🚨 Alto',
      _        => '✅ Nenhum',
    };

    final items = [
      ('🧑', 'Vistoriador', capturedBy),
      ('📋', 'Referência', referenceId),
      ('🕐', 'Capturado em', dataFormatada),
      ('⏱️', 'Relógio', clockSuspicious ? '⚠️ Suspeito' : '✅ Normal'),
      ('📍', 'Localização', locationSuspicious ? '⚠️ Suspeita' : '✅ Normal'),
      ('🔐', 'Play Integrity', hasIntegrityToken ? '✅ Verificado' : '❌ Indisponível'),
      ('🛡️', 'Risco', riskLabel),
    ];

    return GridView.builder(
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: 2,
        crossAxisSpacing: 8,
        mainAxisSpacing: 8,
        childAspectRatio: 2.2,
      ),
      itemCount: items.length,
      itemBuilder: (_, i) {
        final (emoji, label, value) = items[i];
        return Card(
          child: Padding(
            padding: const EdgeInsets.all(10),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Text(
                  '$emoji $label',
                  style: const TextStyle(color: kCinzaT, fontSize: 11),
                ),
                const SizedBox(height: 2),
                Text(
                  value,
                  style: const TextStyle(
                    fontWeight: FontWeight.w600,
                    fontSize: 13,
                  ),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}

class _HashCard extends StatelessWidget {
  const _HashCard({required this.hash, required this.onCopy});

  final String hash;
  final VoidCallback onCopy;

  String _formatarHash(String h) {
    final buf = StringBuffer();
    for (int i = 0; i < h.length; i += 32) {
      if (i > 0) buf.write('\n');
      buf.write(h.substring(i, (i + 32).clamp(0, h.length)));
    }
    return buf.toString();
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: kNavy,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Expanded(
                child: Text(
                  'SHA-256 DO FRAME',
                  style: TextStyle(
                    color: kBlueL,
                    fontSize: 11,
                    fontWeight: FontWeight.bold,
                    letterSpacing: 1.5,
                  ),
                ),
              ),
              IconButton(
                onPressed: onCopy,
                icon: const Icon(Icons.copy, color: kBlueL, size: 20),
                tooltip: 'Copiar hash',
                padding: EdgeInsets.zero,
                constraints: const BoxConstraints(),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            _formatarHash(hash),
            style: const TextStyle(
              color: Colors.white,
              fontFamily: 'monospace',
              fontSize: 13,
              height: 1.6,
            ),
          ),
        ],
      ),
    );
  }
}

class _QrCard extends StatelessWidget {
  const _QrCard({required this.url});

  final String url;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          children: [
            ClipRRect(
              borderRadius: BorderRadius.circular(8),
              child: QrImageView(
                data: url,
                size: 180,
                eyeStyle: const QrEyeStyle(
                  eyeShape: QrEyeShape.square,
                  color: kNavy,
                ),
                dataModuleStyle: const QrDataModuleStyle(
                  dataModuleShape: QrDataModuleShape.square,
                  color: kNavy,
                ),
                backgroundColor: Colors.white,
              ),
            ),
            const SizedBox(height: 8),
            const Text(
              'Escaneie para verificar autenticidade',
              style: TextStyle(color: kCinzaT, fontSize: 12),
              textAlign: TextAlign.center,
            ),
          ],
        ),
      ),
    );
  }
}

class _ActionButtons extends StatelessWidget {
  const _ActionButtons({
    required this.verifierUrl,
    required this.manifestJson,
    required this.session,
    required this.capturedBy,
  });

  final String verifierUrl;
  final String manifestJson;
  final Map<String, dynamic> session;
  final String capturedBy;

  void _verManifesto(BuildContext context) {
    String jsonFormatado;
    try {
      final decoded = jsonDecode(manifestJson);
      jsonFormatado = const JsonEncoder.withIndent('  ').convert(decoded);
    } catch (_) {
      jsonFormatado = manifestJson;
    }

    showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Manifesto C2PA'),
        content: SizedBox(
          width: double.maxFinite,
          child: SingleChildScrollView(
            child: SelectableText(
              jsonFormatado,
              style: const TextStyle(fontFamily: 'monospace', fontSize: 11),
            ),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(),
            child: const Text('Fechar'),
          ),
        ],
      ),
    );
  }

  Future<void> _abrirVerifier(BuildContext context) async {
    final uri = Uri.parse(verifierUrl);
    if (!await launchUrl(uri, mode: LaunchMode.externalApplication)) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
              content: Text('Não foi possível abrir o verificador')),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: Colors.white,
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.08),
            blurRadius: 8,
            offset: const Offset(0, -2),
          ),
        ],
      ),
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [

              // Nível 3 — Ver Manifesto (ação técnica, menor destaque)
              TextButton.icon(
                onPressed: () => _verManifesto(context),
                icon: const Icon(Icons.article_outlined, size: 16),
                label: const Text('Ver Manifesto C2PA'),
                style: TextButton.styleFrom(
                  foregroundColor: kCinzaT,
                  textStyle: const TextStyle(fontSize: 13),
                ),
              ),

              const SizedBox(height: 8),

              // Nível 2 — Ações de demonstração (lado a lado)
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton.icon(
                      onPressed: () => _abrirVerifier(context),
                      icon: const Icon(Icons.verified_outlined, size: 18),
                      label: const Text('Verificar no Provvi'),
                      style: OutlinedButton.styleFrom(
                        foregroundColor: kAzul,
                        side: const BorderSide(color: kAzul),
                        padding: const EdgeInsets.symmetric(vertical: 12),
                        textStyle: const TextStyle(fontSize: 13),
                      ),
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: OutlinedButton.icon(
                      onPressed: () => Navigator.pushNamed(
                        context,
                        '/tamper',
                        arguments: {
                          'session': session,
                          'capturedBy': capturedBy,
                        },
                      ),
                      icon: const Icon(Icons.warning_amber_outlined, size: 18),
                      label: const Text('Simular Adulteração'),
                      style: OutlinedButton.styleFrom(
                        foregroundColor: kRed,
                        side: BorderSide(color: kRed.withValues(alpha: 0.6)),
                        padding: const EdgeInsets.symmetric(vertical: 12),
                        textStyle: const TextStyle(fontSize: 13),
                      ),
                    ),
                  ),
                ],
              ),

              const SizedBox(height: 10),

              // Nível 1 — Nova Vistoria (ação primária, largura total)
              SizedBox(
                width: double.infinity,
                child: FilledButton.icon(
                  onPressed: () => Navigator.pushNamedAndRemoveUntil(
                    context,
                    '/',
                    (_) => false,
                  ),
                  icon: const Icon(Icons.refresh),
                  label: const Text(
                    'Nova Vistoria',
                    style: TextStyle(
                        fontSize: 15, fontWeight: FontWeight.bold),
                  ),
                  style: FilledButton.styleFrom(
                    backgroundColor: kAzul,
                    padding: const EdgeInsets.symmetric(vertical: 14),
                  ),
                ),
              ),

            ],
          ),
        ),
      ),
    );
  }
}
