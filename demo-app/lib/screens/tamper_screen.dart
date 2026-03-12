import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../theme.dart';

class TamperScreen extends StatefulWidget {
  const TamperScreen({super.key});

  @override
  State<TamperScreen> createState() => _TamperScreenState();
}

class _TamperScreenState extends State<TamperScreen> {
  bool _adulterado = false;
  bool _argsLoaded = false;
  late Map<String, dynamic> _session;
  late String _capturedBy;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (!_argsLoaded) {
      final args =
          ModalRoute.of(context)!.settings.arguments as Map<String, dynamic>;
      _session = args['session'] as Map<String, dynamic>;
      _capturedBy = (args['capturedBy'] as String?) ?? '';
      _argsLoaded = true;
    }
  }

  String _gerarHashAdulterado(String hashOriginal) {
    final prefixo =
        hashOriginal.substring(0, 8).split('').reversed.join();
    return prefixo + hashOriginal.substring(8);
  }

  String _formatarTimestamp(int ms) {
    final dt = DateTime.fromMillisecondsSinceEpoch(ms);
    return '${dt.day.toString().padLeft(2, '0')}/'
        '${dt.month.toString().padLeft(2, '0')}/'
        '${dt.year} '
        '${dt.hour.toString().padLeft(2, '0')}:'
        '${dt.minute.toString().padLeft(2, '0')}';
  }

  void _mostrarExplicacao(BuildContext context) {
    showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Como funciona a detecção'),
        content: const SingleChildScrollView(
          child: Text(
            'O SHA-256 funciona como uma impressão digital matemática: se um único pixel '
            'for alterado, a impressão digital muda completamente. O manifesto C2PA '
            'armazena a impressão digital original, assinada com certificado ICP-Brasil. '
            'Se a impressão digital da imagem atual não bater com a do manifesto, a '
            'verificação falha — é matematicamente impossível adulterar sem que isso seja detectado.',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(),
            child: const Text('Entendi'),
          ),
        ],
      ),
    );
  }

  Future<void> _abrirVerifier(BuildContext context) async {
    final sessionId = _session['sessionId'] as String? ?? '';
    final url = Uri.parse(
      'https://ldp4x25jnk2mrppz75ts2kiq7a0bylxw.lambda-url.sa-east-1.on.aws/?session_id=$sessionId',
    );
    await launchUrl(url, mode: LaunchMode.externalApplication);
  }

  @override
  Widget build(BuildContext context) {
    if (!_argsLoaded) {
      return const Scaffold(
          body: Center(child: CircularProgressIndicator()));
    }

    final hashOriginal = _session['frameHashHex'] as String? ?? '';
    final capturedAtMs = (_session['capturedAtMs'] is int
        ? _session['capturedAtMs'] as int
        : (_session['capturedAtMs'] as num?)?.toInt()) ?? 0;
    final timestamp    = _formatarTimestamp(capturedAtMs);
    final hashAdulterado  = _gerarHashAdulterado(hashOriginal);

    return Scaffold(
      appBar: AppBar(title: const Text('Simulação de Adulteração')),
      body: SingleChildScrollView(
        padding: EdgeInsets.fromLTRB(
          16, 16, 16, MediaQuery.of(context).padding.bottom + 24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _OriginalCard(
              hash: hashOriginal,
              capturedBy: _capturedBy,
              timestamp: timestamp,
            ),
            const SizedBox(height: 12),
            const Card(
              child: Padding(
                padding: EdgeInsets.all(16),
                child: Text(
                  '"O SHA-256 é a impressão digital matemática desta imagem. '
                  'Está criptograficamente vinculado ao dispositivo, localização e '
                  'momento da captura pelo manifesto C2PA."',
                  style: TextStyle(
                    fontStyle: FontStyle.italic,
                    color: kCinzaT,
                    fontSize: 13,
                  ),
                  textAlign: TextAlign.center,
                ),
              ),
            ),
            const SizedBox(height: 16),
            if (!_adulterado)
              FilledButton.icon(
                onPressed: () => setState(() => _adulterado = true),
                icon: const Icon(Icons.edit_outlined),
                label: const Text('Simular adulteração da imagem'),
                style: FilledButton.styleFrom(
                  backgroundColor: kRed,
                  padding: const EdgeInsets.symmetric(vertical: 16),
                ),
              ),
            AnimatedSize(
              duration: const Duration(milliseconds: 400),
              curve: Curves.easeOut,
              child: _adulterado
                  ? Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        _ComparisonRow(
                          hashOriginal: hashOriginal,
                          hashAdulterado: hashAdulterado,
                        ),
                        const SizedBox(height: 16),
                        const _ResultadoCard(),
                        const SizedBox(height: 16),
                        Row(
                          children: [
                            Expanded(
                              child: OutlinedButton.icon(
                                onPressed: () =>
                                    _mostrarExplicacao(context),
                                icon: const Icon(Icons.help_outline,
                                    size: 18),
                                label: const Text('Entender o que aconteceu'),
                              ),
                            ),
                            const SizedBox(width: 8),
                            Expanded(
                              child: FilledButton.icon(
                                onPressed: () => _abrirVerifier(context),
                                icon: const Icon(Icons.verified_outlined,
                                    size: 18),
                                label: const Text('Verificar no Provvi'),
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 8),
                        TextButton.icon(
                          onPressed: () => Navigator.pop(context),
                          icon: const Icon(Icons.arrow_back),
                          label: const Text('Voltar'),
                        ),
                      ],
                    )
                  : const SizedBox.shrink(),
            ),
            if (!_adulterado) ...[
              const SizedBox(height: 8),
              TextButton.icon(
                onPressed: () => Navigator.pop(context),
                icon: const Icon(Icons.arrow_back),
                label: const Text('Voltar'),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _OriginalCard extends StatelessWidget {
  const _OriginalCard({
    required this.hash,
    required this.capturedBy,
    required this.timestamp,
  });

  final String hash;
  final String capturedBy;
  final String timestamp;

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
                  'IMAGEM ORIGINAL',
                  style: TextStyle(
                    color: kBlueL,
                    fontSize: 11,
                    fontWeight: FontWeight.bold,
                    letterSpacing: 1.5,
                  ),
                ),
              ),
              Container(
                padding: const EdgeInsets.symmetric(
                    horizontal: 8, vertical: 3),
                decoration: BoxDecoration(
                  color: kVerdeL,
                  borderRadius: BorderRadius.circular(4),
                ),
                child: const Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(Icons.check_circle, color: kVerde, size: 12),
                    SizedBox(width: 4),
                    Text(
                      'ASSINATURA VÁLIDA',
                      style: TextStyle(
                        color: kVerde,
                        fontSize: 10,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Text(
            _formatarHash(hash),
            style: const TextStyle(
              color: Colors.white,
              fontFamily: 'monospace',
              fontSize: 12,
              height: 1.6,
            ),
          ),
          const SizedBox(height: 8),
          Text(
            'Vinculado a: $capturedBy · $timestamp',
            style: const TextStyle(color: Colors.white54, fontSize: 11),
          ),
        ],
      ),
    );
  }
}

class _ComparisonRow extends StatelessWidget {
  const _ComparisonRow({
    required this.hashOriginal,
    required this.hashAdulterado,
  });

  final String hashOriginal;
  final String hashAdulterado;

  @override
  Widget build(BuildContext context) {
    final origTrunc =
        hashOriginal.length > 32 ? '${hashOriginal.substring(0, 32)}...' : hashOriginal;
    final adultTrunc =
        hashAdulterado.length > 32 ? '${hashAdulterado.substring(0, 32)}...' : hashAdulterado;

    return Column(
      children: [
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: _ComparisonCard(
                label: 'ORIGINAL',
                badgeText: '✅ VÁLIDO',
                isValid: true,
                prefixo: origTrunc.substring(0, 8),
                sufixo: origTrunc.substring(8),
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: _ComparisonCard(
                label: 'ADULTERADO',
                badgeText: '❌ INVÁLIDO',
                isValid: false,
                prefixo: adultTrunc.substring(0, 8),
                sufixo: adultTrunc.substring(8),
              ),
            ),
          ],
        ),
        const SizedBox(height: 8),
        Text(
          '↑ 8 caracteres alterados de ${hashOriginal.length * 4} bits totais',
          style: const TextStyle(color: kCinzaT, fontSize: 11),
          textAlign: TextAlign.center,
        ),
      ],
    );
  }
}

class _ComparisonCard extends StatelessWidget {
  const _ComparisonCard({
    required this.label,
    required this.badgeText,
    required this.isValid,
    required this.prefixo,
    required this.sufixo,
  });

  final String label;
  final String badgeText;
  final bool isValid;
  final String prefixo;
  final String sufixo;

  @override
  Widget build(BuildContext context) {
    final bg     = isValid ? kVerdeL : kRedL;
    final border = isValid ? kVerde  : kRed;
    final color  = isValid ? kVerde  : kRed;

    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: bg,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: border),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            label,
            style: TextStyle(
              color: color,
              fontSize: 11,
              fontWeight: FontWeight.bold,
              letterSpacing: 1.2,
            ),
          ),
          const SizedBox(height: 6),
          Text(
            badgeText,
            style: TextStyle(
              color: color,
              fontWeight: FontWeight.bold,
              fontSize: 12,
            ),
          ),
          const SizedBox(height: 8),
          RichText(
            text: TextSpan(
              children: [
                TextSpan(
                  text: prefixo,
                  style: TextStyle(
                    fontFamily: 'monospace',
                    fontSize: 11,
                    fontWeight: FontWeight.bold,
                    color: color,
                    backgroundColor: color.withValues(alpha: 0.15),
                  ),
                ),
                TextSpan(
                  text: sufixo,
                  style: TextStyle(
                    fontFamily: 'monospace',
                    fontSize: 11,
                    color: color.withValues(alpha: 0.8),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _ResultadoCard extends StatelessWidget {
  const _ResultadoCard();

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: kRedL,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: kRed),
      ),
      child: const Column(
        children: [
          Icon(Icons.cancel, color: kRed, size: 40),
          SizedBox(height: 8),
          Text(
            'ASSINATURA INVÁLIDA',
            style: TextStyle(
              color: kRed,
              fontSize: 16,
              fontWeight: FontWeight.bold,
            ),
          ),
          SizedBox(height: 8),
          Text(
            'A impressão digital da imagem não corresponde\n'
            'ao manifesto C2PA assinado.\n\n'
            'Esta imagem seria rejeitada pelo verificador Provvi.',
            style: TextStyle(color: kRed, fontSize: 13),
            textAlign: TextAlign.center,
          ),
        ],
      ),
    );
  }
}
