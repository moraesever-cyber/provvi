import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:intl/intl.dart';

/// Tela de coleta rápida de imagens para calibração do RecaptureDetector.
///
/// Captura imagens via SDK Provvi (sem formulário, sem upload ao backend)
/// e salva diretamente na estrutura de pastas que o app de diagnóstico espera:
///   <pastaRaiz>/real/        → imagens de objetos físicos reais
///   <pastaRaiz>/recapture/   → imagens de telas (tentativas de recaptura)
///
/// Não faz upload ao backend. Não exige campos de vistoria.
class DatasetCollectionScreen extends StatefulWidget {
  const DatasetCollectionScreen({super.key});

  @override
  State<DatasetCollectionScreen> createState() =>
      _DatasetCollectionScreenState();
}

class _DatasetCollectionScreenState extends State<DatasetCollectionScreen> {
  static const _channel = MethodChannel('br.com.provvi/sdk');

  // Nome da pasta raiz dentro de Downloads
  static const _rootFolderName = 'provvi_dataset';

  int _countReal = 0;
  int _countRecapture = 0;
  String? _lastFilename;
  String? _lastLabel;
  File? _lastImageFile;
  bool _isCapturing = false;
  String? _errorMessage;

  @override
  void initState() {
    super.initState();
    _loadCounts();
  }

  // Conta imagens já existentes nas pastas para retomar sessões anteriores
  Future<void> _loadCounts() async {
    final root = await _getRootDir();
    final realDir      = Directory('${root.path}/real');
    final recaptureDir = Directory('${root.path}/recapture');

    int real  = 0;
    int recap = 0;

    if (await realDir.exists()) {
      real = (await realDir.list().toList())
          .whereType<File>()
          .where((f) => f.path.endsWith('.jpg'))
          .length;
    }
    if (await recaptureDir.exists()) {
      recap = (await recaptureDir.list().toList())
          .whereType<File>()
          .where((f) => f.path.endsWith('.jpg'))
          .length;
    }

    if (mounted) {
      setState(() {
        _countReal      = real;
        _countRecapture = recap;
      });
    }
  }

  Future<Directory> _getRootDir() async {
    // Salva em Downloads para fácil acesso via cabo USB ou gerenciador de arquivos
    final downloads = Directory('/storage/emulated/0/Download/$_rootFolderName');
    if (!await downloads.exists()) {
      await downloads.create(recursive: true);
    }
    return downloads;
  }

  Future<void> _capture(String label) async {
    if (_isCapturing) return;

    setState(() {
      _isCapturing  = true;
      _errorMessage = null;
    });

    try {
      final result = await _channel.invokeMapMethod<String, dynamic>(
        'captureDataset',
        {'label': label},
      );

      if (result == null) throw Exception('Resultado nulo do SDK');

      final imageBytes = result['imageJpegBytes'] as Uint8List?;
      if (imageBytes == null) throw Exception('Imagem não retornada pelo SDK');

      // Salva na subpasta correta com timestamp como nome
      final root   = await _getRootDir();
      final subDir = Directory('${root.path}/$label');
      if (!await subDir.exists()) await subDir.create();

      final timestamp = DateFormat('yyyyMMdd_HHmmss').format(DateTime.now());
      final filename  = '$timestamp.jpg';
      final file      = File('${subDir.path}/$filename');
      await file.writeAsBytes(imageBytes);

      setState(() {
        _lastFilename  = '$label/$filename';
        _lastLabel     = label;
        _lastImageFile = file;
        if (label == 'real') {
          _countReal++;
        } else {
          _countRecapture++;
        }
      });
    } on PlatformException catch (e) {
      // RECAPTURE_SUSPECTED não bloqueia quando o label é "recapture" —
      // queremos capturar recapturas mesmo que o detector as identifique.
      // Neste caso o SDK retornou erro mas a imagem não foi salva; informa ao usuário.
      if (e.code == 'RECAPTURE_SUSPECTED' && label == 'recapture') {
        setState(() {
          _errorMessage =
              'Recaptura detectada pelo SDK (esperado). ${e.message}';
        });
      } else {
        setState(() {
          _errorMessage = '${e.code}: ${e.message}';
        });
      }
    } catch (e) {
      setState(() {
        _errorMessage = e.toString();
      });
    } finally {
      setState(() => _isCapturing = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Coleta de Dataset'),
        actions: [
          IconButton(
            icon: const Icon(Icons.folder_open),
            tooltip: 'Pasta: Downloads/$_rootFolderName',
            onPressed: () {
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(
                  content: Text(
                    'Salvo em: Downloads/provvi_dataset/real/ e /recapture/',
                  ),
                  duration: Duration(seconds: 3),
                ),
              );
            },
          ),
        ],
      ),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          children: [
            // Contadores
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceEvenly,
              children: [
                _CounterBadge(
                  label: 'Real',
                  count: _countReal,
                  color: Colors.green,
                ),
                _CounterBadge(
                  label: 'Recaptura',
                  count: _countRecapture,
                  color: Colors.orange,
                ),
              ],
            ),

            const SizedBox(height: 40),

            // Botões de captura
            Row(
              children: [
                Expanded(
                  child: _CaptureButton(
                    label: 'REAL',
                    sublabel: 'Objeto físico',
                    color: Colors.green,
                    isLoading: _isCapturing,
                    onPressed: () => _capture('real'),
                  ),
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: _CaptureButton(
                    label: 'RECAPTURA',
                    sublabel: 'Foto de tela',
                    color: Colors.orange,
                    isLoading: _isCapturing,
                    onPressed: () => _capture('recapture'),
                  ),
                ),
              ],
            ),

            const SizedBox(height: 32),

            // Feedback da última captura
            if (_lastImageFile != null) ...[
              Row(
                children: [
                  ClipRRect(
                    borderRadius: BorderRadius.circular(8),
                    child: Image.file(
                      _lastImageFile!,
                      width: 72,
                      height: 72,
                      fit: BoxFit.cover,
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          _lastLabel == 'real' ? '✅ Real' : '🖥️ Recaptura',
                          style: TextStyle(
                            fontWeight: FontWeight.bold,
                            color: _lastLabel == 'real'
                                ? Colors.green
                                : Colors.orange,
                          ),
                        ),
                        Text(
                          _lastFilename ?? '',
                          style: const TextStyle(
                            fontSize: 12,
                            color: Colors.grey,
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ],

            // Erro
            if (_errorMessage != null) ...[
              const SizedBox(height: 16),
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.red.shade50,
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: Colors.red.shade200),
                ),
                child: Text(
                  _errorMessage!,
                  style: TextStyle(color: Colors.red.shade700, fontSize: 13),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _CounterBadge extends StatelessWidget {
  final String label;
  final int count;
  final Color color;

  const _CounterBadge({
    required this.label,
    required this.count,
    required this.color,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Text(
          '$count',
          style: TextStyle(
            fontSize: 48,
            fontWeight: FontWeight.bold,
            color: color,
          ),
        ),
        Text(label, style: const TextStyle(fontSize: 14, color: Colors.grey)),
      ],
    );
  }
}

class _CaptureButton extends StatelessWidget {
  final String label;
  final String sublabel;
  final Color color;
  final bool isLoading;
  final VoidCallback onPressed;

  const _CaptureButton({
    required this.label,
    required this.sublabel,
    required this.color,
    required this.isLoading,
    required this.onPressed,
  });

  @override
  Widget build(BuildContext context) {
    return ElevatedButton(
      onPressed: isLoading ? null : onPressed,
      style: ElevatedButton.styleFrom(
        backgroundColor: color,
        foregroundColor: Colors.white,
        padding: const EdgeInsets.symmetric(vertical: 24),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(12),
        ),
      ),
      child: isLoading
          ? const SizedBox(
              width: 24,
              height: 24,
              child: CircularProgressIndicator(
                color: Colors.white,
                strokeWidth: 2,
              ),
            )
          : Column(
              children: [
                Text(
                  label,
                  style: const TextStyle(
                    fontSize: 18,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                Text(
                  sublabel,
                  style: const TextStyle(fontSize: 12),
                ),
              ],
            ),
    );
  }
}
