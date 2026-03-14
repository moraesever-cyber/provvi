import 'package:flutter/material.dart';

import '../dataset_collection_screen.dart';
import '../services/provvi_channel.dart';
import '../theme.dart';
import '../widgets/provvi_logo.dart';

class IdentificationScreen extends StatefulWidget {
  const IdentificationScreen({super.key});

  @override
  State<IdentificationScreen> createState() => _IdentificationScreenState();
}

class _IdentificationScreenState extends State<IdentificationScreen> {
  bool _permissionsOk = false;
  bool _loading = false;

  final _capturedByController = TextEditingController();
  final _referenceIdController = TextEditingController();

  @override
  void initState() {
    super.initState();
    _checkPermissions();
  }

  Future<void> _checkPermissions() async {
    final ok = await ProvviChannel.instance.checkPermissions();
    if (mounted) setState(() => _permissionsOk = ok);
  }

  Future<void> _iniciarVistoria() async {
    if (_loading) return;

    if (!_permissionsOk) {
      setState(() => _loading = true);
      final granted = await ProvviChannel.instance.requestPermissions();
      if (mounted) {
        setState(() {
          _permissionsOk = granted;
          _loading = false;
        });
      }
      if (!granted) return;
    }

    final capturedBy = _capturedByController.text.trim().isNotEmpty
        ? _capturedByController.text.trim()
        : 'Vistoriador Demo';

    final referenceId = _referenceIdController.text.trim().isNotEmpty
        ? _referenceIdController.text.trim()
        : 'demo-${DateTime.now().millisecondsSinceEpoch}';

    if (mounted) {
      Navigator.pushNamed(context, '/capture', arguments: {
        'capturedBy': capturedBy,
        'referenceId': referenceId,
      });
    }
  }

  @override
  void dispose() {
    _capturedByController.dispose();
    _referenceIdController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Column(
        children: [
          _buildHeader(),
          Expanded(
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(24),
              child: Column(
                children: [
                  if (!_permissionsOk) ...[
                    _PermissionBanner(
                      onConceder: _loading ? null : _iniciarVistoria,
                    ),
                    const SizedBox(height: 16),
                  ],
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(24),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            'Dados da Vistoria',
                            style: Theme.of(context)
                                .textTheme
                                .titleMedium
                                ?.copyWith(fontWeight: FontWeight.bold),
                          ),
                          const SizedBox(height: 20),
                          TextField(
                            controller: _capturedByController,
                            decoration: const InputDecoration(
                              labelText: 'Nome do vistoriador',
                              hintText: 'Ex: João Silva',
                              prefixIcon: Icon(Icons.person_outline),
                            ),
                          ),
                          const SizedBox(height: 16),
                          TextField(
                            controller: _referenceIdController,
                            decoration: const InputDecoration(
                              labelText: 'Referência da vistoria',
                              hintText: 'Ex: ABC-1234, Sinistro #9872',
                              prefixIcon: Icon(Icons.tag),
                            ),
                          ),
                          const SizedBox(height: 24),
                          SizedBox(
                            width: double.infinity,
                            child: FilledButton.icon(
                              onPressed: _loading ? null : _iniciarVistoria,
                              icon: _loading
                                  ? const SizedBox(
                                      width: 18,
                                      height: 18,
                                      child: CircularProgressIndicator(
                                        strokeWidth: 2,
                                        color: Colors.white,
                                      ),
                                    )
                                  : const Icon(Icons.shield_outlined),
                              label: const Text('Iniciar Vistoria'),
                              style: FilledButton.styleFrom(
                                padding: const EdgeInsets.symmetric(
                                    vertical: 16),
                              ),
                            ),
                          ),
                          const SizedBox(height: 8),
                          SizedBox(
                            width: double.infinity,
                            child: TextButton.icon(
                              onPressed: () => Navigator.push(
                                context,
                                MaterialPageRoute(
                                  builder: (_) =>
                                      const DatasetCollectionScreen(),
                                ),
                              ),
                              icon: const Icon(Icons.dataset),
                              label: const Text('Coletar Dataset'),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildHeader() {
    return Container(
      width: double.infinity,
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          stops: [0.0, 0.7, 1.0],
          colors: [kNavy, kNavy, Colors.white],
        ),
      ),
      child: SafeArea(
        bottom: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(24, 32, 24, 40),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const ProvviLogo(size: 40, onDark: true),
              const SizedBox(height: 6),
              Text(
                'Autenticidade verificável',
                style: TextStyle(
                  color: Colors.white.withValues(alpha: 0.75),
                  fontSize: 14,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _PermissionBanner extends StatelessWidget {
  const _PermissionBanner({this.onConceder});

  final VoidCallback? onConceder;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: kRedL,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: kRed.withValues(alpha: 0.3)),
      ),
      child: Row(
        children: [
          const Icon(Icons.warning_amber_rounded,
              color: Colors.orange, size: 20),
          const SizedBox(width: 8),
          const Expanded(
            child: Text(
              'Câmera e localização são necessárias',
              style: TextStyle(fontSize: 13),
            ),
          ),
          TextButton(
            onPressed: onConceder,
            child: const Text('Conceder'),
          ),
        ],
      ),
    );
  }
}
