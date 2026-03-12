import 'dart:async';

import 'package:camera/camera.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../services/provvi_channel.dart';
import '../theme.dart';
import '../widgets/provvi_logo.dart';

class CaptureScreen extends StatefulWidget {
  const CaptureScreen({super.key});

  @override
  State<CaptureScreen> createState() => _CaptureScreenState();
}

class _CaptureScreenState extends State<CaptureScreen> {
  static const _fases = [
    (Icons.camera_alt_outlined, 'Inicializando câmera...'),
    (Icons.fingerprint, 'Calculando hash SHA-256...'),
    (Icons.verified_user_outlined, 'Assinando manifesto C2PA...'),
    (Icons.cloud_upload_outlined, 'Sincronizando com backend...'),
  ];

  // Câmera
  CameraController? _cameraController;
  bool _cameraReady = false;

  // Estados
  bool _capturing = false;
  String? _erroCodigo;
  String? _erroMensagem;

  // Animação de fases
  int _faseIndex = 0;
  Timer? _faseTimer;

  // Args da rota
  bool _argsLoaded = false;
  late String _capturedBy;
  late String _referenceId;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (!_argsLoaded) {
      final args =
          ModalRoute.of(context)!.settings.arguments as Map<String, dynamic>;
      _capturedBy = args['capturedBy'] as String;
      _referenceId = args['referenceId'] as String;
      _argsLoaded = true;
      _initCamera();
    }
  }

  Future<void> _initCamera() async {
    try {
      final cameras = await availableCameras();
      if (cameras.isEmpty) {
        if (mounted) {
          setState(() {
            _erroCodigo = 'CAMERA_UNAVAILABLE';
            _erroMensagem = 'Câmera não disponível neste dispositivo.';
          });
        }
        return;
      }
      final camera = cameras.firstWhere(
        (c) => c.lensDirection == CameraLensDirection.back,
        orElse: () => cameras.first,
      );
      final controller = CameraController(
        camera,
        ResolutionPreset.high,
        enableAudio: false,
      );
      await controller.initialize();
      if (mounted) {
        _cameraController = controller;
        setState(() => _cameraReady = true);
      } else {
        await controller.dispose();
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _erroCodigo = 'CAMERA_UNAVAILABLE';
          _erroMensagem = 'Erro ao inicializar câmera: ${e.toString()}';
        });
      }
    }
  }

  Future<void> _onShutterPressed() async {
    if (_capturing) return;
    setState(() {
      _capturing = true;
      _faseIndex = 0;
    });
    _iniciarAnimacaoFases();

    // Liberar câmera antes de chamar o SDK — o SDK precisa de acesso exclusivo
    await _cameraController?.dispose();
    _cameraController = null;

    try {
      final result = await ProvviChannel.instance.capture(
        referenceId: _referenceId,
        capturedBy: _capturedBy,
      );
      _faseTimer?.cancel();
      if (mounted) {
        Navigator.pushReplacementNamed(context, '/result', arguments: {
          'session': result,
          'capturedBy': _capturedBy,
          'referenceId': _referenceId,
        });
      }
    } on PlatformException catch (e) {
      _faseTimer?.cancel();
      if (mounted) {
        setState(() {
          _capturing = false;
          _erroCodigo = e.code;
          _erroMensagem = e.message;
        });
        // Não reinicializa a câmera aqui — o SDK pode ainda não ter liberado o recurso.
        // A reinicialização ocorre quando o usuário clica em "Tentar novamente".
      }
    }
  }

  void _iniciarAnimacaoFases() {
    _faseTimer?.cancel();
    _faseTimer = Timer.periodic(const Duration(milliseconds: 900), (_) {
      if (mounted && _faseIndex < _fases.length - 1) {
        setState(() => _faseIndex++);
      }
    });
  }

  Future<void> _tentarNovamente() async {
    setState(() {
      _erroCodigo = null;
      _erroMensagem = null;
      _cameraReady = false;
    });
    await _initCamera();
  }

  @override
  void dispose() {
    _faseTimer?.cancel();
    _cameraController?.dispose();
    super.dispose();
  }

  String _traduzirErro(String code, String? message) {
    return switch (code) {
      'PERMISSION_DENIED' =>
        'Permissão negada — conceda acesso à câmera e localização.',
      'DEVICE_COMPROMISED' =>
        'Dispositivo comprometido — captura bloqueada por segurança.',
      'MOCK_LOCATION' =>
        'Localização simulada detectada — desative apps de GPS falso.',
      'RECAPTURE_SUSPECTED' =>
        'Recaptura detectada — fotografe o objeto real, não uma tela.',
      'SIGNING_FAILED' =>
        'Falha na assinatura C2PA: ${message ?? "erro desconhecido"}',
      'CAPTURE_ERROR' => 'Erro na captura: ${message ?? "erro desconhecido"}',
      'CAMERA_UNAVAILABLE' => message ?? 'Câmera indisponível.',
      _ => 'Erro inesperado ($code): ${message ?? ""}',
    };
  }

  IconData _erroIcon(String code) {
    return switch (code) {
      'MOCK_LOCATION'      => Icons.gps_off,
      'DEVICE_COMPROMISED' => Icons.security,
      _                    => Icons.error_outline,
    };
  }

  @override
  Widget build(BuildContext context) {
    if (_erroCodigo != null) return _buildErro();
    if (_capturing) return _buildLoading();
    if (!_cameraReady || _cameraController == null) {
      return const Scaffold(
        backgroundColor: kNavy,
        body: Center(child: CircularProgressIndicator(color: kBlueL)),
      );
    }
    return _buildPreview();
  }

  Widget _buildPreview() {
    return Scaffold(
      extendBodyBehindAppBar: true,
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        elevation: 0,
        foregroundColor: Colors.white,
        shadowColor: Colors.transparent,
        surfaceTintColor: Colors.transparent,
      ),
      body: Stack(
        fit: StackFit.expand,
        children: [
          // FittedBox.cover mantém o aspect ratio da câmera preenchendo a tela
          // sem stretch — comportamento padrão de apps de câmera.
          //
          // previewSize reporta as dimensões na orientação natural do sensor (landscape
          // na maioria dos Android). Em portrait trocamos width↔height para que o
          // SizedBox fique portrait-shaped. Em landscape usamos os valores diretos.
          Builder(builder: (context) {
            final isPortrait =
                MediaQuery.of(context).orientation == Orientation.portrait;
            final previewW = isPortrait
                ? (_cameraController!.value.previewSize?.height ?? 9)
                : (_cameraController!.value.previewSize?.width  ?? 16);
            final previewH = isPortrait
                ? (_cameraController!.value.previewSize?.width  ?? 16)
                : (_cameraController!.value.previewSize?.height ?? 9);
            return SizedBox.expand(
              child: FittedBox(
                fit: BoxFit.cover,
                child: SizedBox(
                  width:  previewW,
                  height: previewH,
                  child: CameraPreview(_cameraController!),
                ),
              ),
            );
          }),

          // Overlay superior — contexto da vistoria
          Positioned(
            top: MediaQuery.of(context).padding.top + 56,
            left: 16,
            right: 16,
            child: Container(
              padding:
                  const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
              decoration: BoxDecoration(
                color: Colors.black54,
                borderRadius: BorderRadius.circular(10),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    _capturedBy,
                    style: const TextStyle(
                      color: Colors.white,
                      fontWeight: FontWeight.bold,
                      fontSize: 15,
                    ),
                  ),
                  Text(
                    'Ref: $_referenceId',
                    style: const TextStyle(
                        color: Colors.white70, fontSize: 13),
                  ),
                ],
              ),
            ),
          ),

          // Botão shutter
          Positioned(
            bottom: MediaQuery.of(context).padding.bottom + 40,
            left: 0,
            right: 0,
            child: Center(
              child: GestureDetector(
                onTap: _onShutterPressed,
                child: Container(
                  width: 72,
                  height: 72,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: Colors.white,
                    border: Border.all(color: kBlueL, width: 4),
                    boxShadow: const [
                      BoxShadow(
                        color: Colors.black38,
                        blurRadius: 12,
                        spreadRadius: 2,
                      ),
                    ],
                  ),
                  child:
                      const Icon(Icons.camera_alt, color: kNavy, size: 32),
                ),
              ),
            ),
          ),

          // Label abaixo do shutter
          Positioned(
            bottom: MediaQuery.of(context).padding.bottom + 12,
            left: 0,
            right: 0,
            child: const Center(
              child: Text(
                'Toque para capturar',
                style: TextStyle(color: Colors.white70, fontSize: 12),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildLoading() {
    final fase = _fases[_faseIndex];
    return Scaffold(
      backgroundColor: kNavy,
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const ProvviLogo(size: 32, onDark: true),
              const SizedBox(height: 48),
              AnimatedSwitcher(
                duration: const Duration(milliseconds: 400),
                child: Icon(
                  fase.$1,
                  key: ValueKey(_faseIndex),
                  size: 64,
                  color: kBlueL,
                ),
              ),
              const SizedBox(height: 24),
              const CircularProgressIndicator(color: kBlueL, strokeWidth: 3),
              const SizedBox(height: 24),
              AnimatedSwitcher(
                duration: const Duration(milliseconds: 300),
                child: Text(
                  fase.$2,
                  key: ValueKey('text_$_faseIndex'),
                  style: const TextStyle(color: Colors.white, fontSize: 16),
                  textAlign: TextAlign.center,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildErro() {
    return Scaffold(
      backgroundColor: kNavy,
      body: SafeArea(
        child: Center(
          child: Padding(
            padding: const EdgeInsets.all(32),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(
                  _erroIcon(_erroCodigo!),
                  size: 72,
                  color: kRed,
                ),
                const SizedBox(height: 24),
                Text(
                  _traduzirErro(_erroCodigo!, _erroMensagem),
                  style: const TextStyle(color: Colors.white, fontSize: 16),
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 32),
                FilledButton.icon(
                  onPressed: _tentarNovamente,
                  icon: const Icon(Icons.refresh),
                  label: const Text('Tentar novamente'),
                ),
                const SizedBox(height: 12),
                TextButton(
                  onPressed: () => Navigator.pop(context),
                  child: const Text(
                    'Voltar',
                    style: TextStyle(color: Colors.white70),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
