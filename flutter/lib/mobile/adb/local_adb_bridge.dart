import 'package:flutter/services.dart';

class LocalAdbStatus {
  final bool supported;
  final bool connected;
  final bool busy;
  final bool connectionBusy;
  final bool paired;
  final int connectPort;
  final String phase;
  final bool keepAliveSupported;
  final bool? keepAliveExempt;
  final bool keepAliveBusy;
  final String keepAliveError;

  const LocalAdbStatus({
    this.supported = false,
    this.connected = false,
    this.busy = false,
    this.connectionBusy = false,
    this.paired = false,
    this.connectPort = 0,
    this.phase = '',
    this.keepAliveSupported = false,
    this.keepAliveExempt,
    this.keepAliveBusy = false,
    this.keepAliveError = '',
  });

  factory LocalAdbStatus.fromMap(Map<dynamic, dynamic> value) => LocalAdbStatus(
        supported: value['supported'] == true,
        connected: value['connected'] == true,
        busy: value['busy'] == true,
        connectionBusy: (value['connectionBusy'] ?? value['busy']) == true,
        paired: value['paired'] == true,
        connectPort: (value['connectPort'] as num?)?.toInt() ?? 0,
        phase: value['phase'] as String? ?? '',
        keepAliveSupported: value['keepAliveSupported'] == true,
        keepAliveExempt: value['keepAliveExempt'] as bool?,
        keepAliveBusy: value['keepAliveBusy'] == true,
        keepAliveError: value['keepAliveError'] as String? ?? '',
      );

  String get keepAliveSummary {
    if (keepAliveExempt == true) return '已豁免电池优化';
    if (keepAliveBusy) return '正在设置电池优化豁免…';
    final state = keepAliveExempt == null ? '状态未确认' : '未豁免电池优化';
    return keepAliveError.isEmpty ? state : '$state：$keepAliveError';
  }
}

class LocalAdbOutput {
  final String text;
  final bool truncated;
  final int? exitCode;

  const LocalAdbOutput(this.text, this.truncated, this.exitCode);
}

/// This bridge only addresses the phone-local plugin, never the product/remote channel.
class LocalAdbBridge {
  static const _channel = MethodChannel('me.ashell/local_adb/v1');

  Future<LocalAdbStatus> status() async {
    final value = await _channel.invokeMapMethod<dynamic, dynamic>('status');
    return LocalAdbStatus.fromMap(value ?? const {});
  }

  Future<void> openSettings() => _channel.invokeMethod<void>('openSettings');

  Future<void> pair(int port, String code) =>
      _channel.invokeMethod<void>('pair', {'port': port, 'code': code});

  Future<Map<dynamic, dynamic>> discover() async =>
      await _channel.invokeMapMethod<dynamic, dynamic>('discover') ?? const {};

  Future<String> pairAndConnect(int port, String code, int connectPort) async =>
      await _channel.invokeMethod<String>('pairConnect',
          {'port': port, 'code': code, 'connectPort': connectPort}) ?? '';

  Future<String> connect(int port) async =>
      await _channel.invokeMethod<String>('connect', {'port': port}) ?? '';

  Future<LocalAdbOutput> shell(String command) async {
    final value = await _channel.invokeMapMethod<dynamic, dynamic>(
        'shell', {'command': command});
    return LocalAdbOutput(
        value?['output'] as String? ?? '', value?['truncated'] == true,
        value?['exitCode'] as int?);
  }

  Future<void> disconnect() => _channel.invokeMethod<void>('disconnect');
  Future<void> cancel() => _channel.invokeMethod<void>('cancel');
}
