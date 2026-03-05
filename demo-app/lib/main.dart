import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  runApp(const ProvviDemoApp());
}

// Canal de comunicação com o SDK nativo Android via Platform Channel
const MethodChannel _channel = MethodChannel('br.com.provvi/sdk');

class ProvviDemoApp extends StatelessWidget {
  const ProvviDemoApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Provvi Demo',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF0D47A1), // Azul escuro
          brightness: Brightness.light,
        ),
        useMaterial3: true,
      ),
      home: const CaptureScreen(),
    );
  }
}

class CaptureScreen extends StatefulWidget {
  const CaptureScreen({super.key});

  @override
  State<CaptureScreen> createState() => _CaptureScreenState();
}

class _CaptureScreenState extends State<CaptureScreen> {
  // Estado principal da tela
  bool _isLoading = false;
  bool _hasPermissions = false;
  Map<String, dynamic>? _lastSession;

  @override
  void initState() {
    super.initState();
    // Verifica permissões logo ao montar a tela
    _verificarPermissoes();
  }

  // Consulta o lado nativo se câmera + localização já foram concedidas
  Future<void> _verificarPermissoes() async {
    try {
      final granted = await _channel.invokeMethod<bool>('checkPermissions');
      setState(() => _hasPermissions = granted ?? false);
    } on PlatformException catch (e) {
      _mostrarErro('Erro ao verificar permissões: ${e.message}');
    }
  }

  // Solicita câmera + ACCESS_FINE_LOCATION ao sistema operacional
  Future<void> _solicitarPermissoes() async {
    setState(() => _isLoading = true);
    try {
      final granted = await _channel.invokeMethod<bool>('requestPermissions');
      setState(() => _hasPermissions = granted ?? false);
      if (!(_hasPermissions)) {
        _mostrarErro('Permissões negadas. Acesse Configurações para habilitá-las.');
      }
    } on PlatformException catch (e) {
      _mostrarErro('Erro ao solicitar permissões: ${e.message}');
    } finally {
      setState(() => _isLoading = false);
    }
  }

  // Invoca o pipeline completo do Provvi SDK via Platform Channel
  Future<void> _capturar() async {
    setState(() {
      _isLoading = true;
      _lastSession = null;
    });

    // ID de referência único por captura para rastreabilidade na demo
    final referenceId = 'demo-${DateTime.now().millisecondsSinceEpoch}';

    try {
      final result = await _channel.invokeMapMethod<String, dynamic>(
        'capture',
        {
          'referenceId': referenceId,
          'capturedBy': 'Provvi Demo App',
        },
      );
      setState(() => _lastSession = result);
    } on PlatformException catch (e) {
      _mostrarErro(_traduzirErro(e.code, e.message));
    } finally {
      setState(() => _isLoading = false);
    }
  }

  // Traduz os códigos de erro do SDK para mensagens em português
  String _traduzirErro(String code, String? message) {
    return switch (code) {
      'PERMISSION_DENIED'    => 'Permissão negada — conceda acesso à câmera e localização.',
      'DEVICE_COMPROMISED'   => 'Dispositivo comprometido — captura bloqueada por segurança.',
      'MOCK_LOCATION'        => 'Localização simulada detectada — desative apps de GPS falso.',
      'SIGNING_FAILED'       => 'Falha na assinatura C2PA: ${message ?? "erro desconhecido"}',
      'CAPTURE_ERROR'        => 'Erro na captura: ${message ?? "erro desconhecido"}',
      _                      => 'Erro inesperado ($code): ${message ?? ""}',
    };
  }

  void _mostrarErro(String mensagem) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(mensagem),
        backgroundColor: Colors.red.shade700,
        behavior: SnackBarBehavior.floating,
      ),
    );
  }

  // Exibe o manifesto C2PA completo em um diálogo com fonte monospace
  void _verManifesto() {
    final manifestJson = _lastSession?['manifestJson'] as String?;
    if (manifestJson == null) return;

    // Formata o JSON com indentação para legibilidade
    String jsonFormatado;
    try {
      final decoded = jsonDecode(manifestJson);
      jsonFormatado = const JsonEncoder.withIndent('  ').convert(decoded);
    } catch (_) {
      jsonFormatado = manifestJson;
    }

    showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Manifesto C2PA'),
        content: SizedBox(
          width: double.maxFinite,
          child: SingleChildScrollView(
            child: SelectableText(
              jsonFormatado,
              style: const TextStyle(
                fontFamily: 'monospace',
                fontSize: 11,
              ),
            ),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('Fechar'),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;

    return Scaffold(
      appBar: AppBar(
        backgroundColor: colorScheme.primary,
        foregroundColor: colorScheme.onPrimary,
        title: const Text('Provvi SDK Demo'),
        centerTitle: true,
      ),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // --- Indicador de permissões ---
            _PermissionBadge(hasPermissions: _hasPermissions),
            const SizedBox(height: 24),

            // --- Botão: solicitar permissões (visível apenas se negadas) ---
            if (!_hasPermissions)
              OutlinedButton.icon(
                onPressed: _isLoading ? null : _solicitarPermissoes,
                icon: const Icon(Icons.security),
                label: const Text('Solicitar Permissões'),
              ),

            if (!_hasPermissions) const SizedBox(height: 16),

            // --- Botão principal de captura ---
            FilledButton.icon(
              onPressed: (_isLoading || !_hasPermissions) ? null : _capturar,
              icon: _isLoading
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                    )
                  : const Icon(Icons.camera_alt),
              label: Text(_isLoading ? 'Capturando...' : 'Capturar com Provvi'),
              style: FilledButton.styleFrom(
                padding: const EdgeInsets.symmetric(vertical: 16),
              ),
            ),

            const SizedBox(height: 32),

            // --- Área de resultado ---
            if (_lastSession != null) _ResultCard(
              session: _lastSession!,
              onVerManifesto: _verManifesto,
            ),
          ],
        ),
      ),
    );
  }
}

// Widget de badge que indica o estado atual das permissões
class _PermissionBadge extends StatelessWidget {
  const _PermissionBadge({required this.hasPermissions});

  final bool hasPermissions;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Icon(
          hasPermissions ? Icons.check_circle : Icons.warning_amber_rounded,
          color: hasPermissions ? Colors.green : Colors.orange,
          size: 20,
        ),
        const SizedBox(width: 8),
        Text(
          hasPermissions
              ? 'Câmera e localização autorizadas'
              : 'Permissões necessárias não concedidas',
          style: TextStyle(
            color: hasPermissions ? Colors.green.shade700 : Colors.orange.shade800,
            fontWeight: FontWeight.w500,
          ),
        ),
      ],
    );
  }
}

// Card exibindo os campos da sessão retornada pelo SDK
class _ResultCard extends StatelessWidget {
  const _ResultCard({required this.session, required this.onVerManifesto});

  final Map<String, dynamic> session;
  final VoidCallback onVerManifesto;

  @override
  Widget build(BuildContext context) {
    // Exibe apenas os primeiros 8 caracteres do UUID para brevidade
    final sessionId = (session['sessionId'] as String? ?? '').substring(0, 8);

    // Primeiros 16 caracteres do hash SHA-256 como prévia
    final fullHash = session['frameHashHex'] as String? ?? '';
    final hashPreview = fullHash.length > 16
        ? '${fullHash.substring(0, 16)}...'
        : fullHash;

    final locationSuspicious = session['locationSuspicious'] as bool? ?? false;
    final hasIntegrityToken  = session['hasIntegrityToken']  as bool? ?? false;

    return Card(
      elevation: 2,
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Sessão capturada',
              style: Theme.of(context).textTheme.titleMedium?.copyWith(
                fontWeight: FontWeight.bold,
              ),
            ),
            const Divider(height: 24),
            _Campo(label: 'Session ID',   value: '$sessionId…'),
            _Campo(label: 'Frame Hash',   value: hashPreview),
            _Campo(
              label: 'Localização',
              value: locationSuspicious ? '⚠️ Suspeita' : '✅ Normal',
            ),
            _Campo(
              label: 'Play Integrity',
              value: hasIntegrityToken ? '✅ Token presente' : '❌ Indisponível',
            ),
            const SizedBox(height: 16),
            SizedBox(
              width: double.infinity,
              child: OutlinedButton.icon(
                onPressed: onVerManifesto,
                icon: const Icon(Icons.article_outlined),
                label: const Text('Ver Manifesto Completo'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// Linha de campo label: valor dentro do ResultCard
class _Campo extends StatelessWidget {
  const _Campo({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 110,
            child: Text(
              label,
              style: const TextStyle(
                color: Colors.grey,
                fontSize: 13,
              ),
            ),
          ),
          Expanded(
            child: Text(
              value,
              style: const TextStyle(
                fontWeight: FontWeight.w500,
                fontSize: 13,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
