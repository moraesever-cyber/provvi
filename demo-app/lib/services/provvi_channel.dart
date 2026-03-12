import 'package:flutter/services.dart';

class ProvviChannel {
  ProvviChannel._();
  static final instance = ProvviChannel._();

  static const _channel = MethodChannel('br.com.provvi/sdk');

  Future<bool> checkPermissions() async {
    return await _channel.invokeMethod<bool>('checkPermissions') ?? false;
  }

  Future<bool> requestPermissions() async {
    return await _channel.invokeMethod<bool>('requestPermissions') ?? false;
  }

  Future<Map<String, dynamic>> capture({
    required String referenceId,
    required String capturedBy,
  }) async {
    final result = await _channel.invokeMapMethod<String, dynamic>(
      'capture',
      {'referenceId': referenceId, 'capturedBy': capturedBy},
    );
    if (result == null) throw PlatformException(code: 'NULL_RESULT');
    return Map<String, dynamic>.from(result);
  }
}
