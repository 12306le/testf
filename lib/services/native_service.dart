import 'dart:async';
import 'package:flutter/services.dart';

/// 与 Kotlin 侧通信的统一入口。
class NativeService {
  NativeService._();
  static final NativeService instance = NativeService._();

  static const _methods = MethodChannel('auto_clicker/methods');
  static const _events = EventChannel('auto_clicker/events');

  final _eventCtrl = StreamController<NativeEvent>.broadcast();
  Stream<NativeEvent> get events => _eventCtrl.stream;
  bool _listening = false;

  void ensureListening() {
    if (_listening) return;
    _listening = true;
    _events.receiveBroadcastStream().listen((raw) {
      if (raw is Map) {
        _eventCtrl.add(NativeEvent(
          raw['event']?.toString() ?? '',
          Map<String, dynamic>.from(
            (raw['data'] as Map?) ?? const {},
          ),
        ));
      }
    }, onError: (_) {});
  }

  Future<Map<String, bool>> getStates() async {
    final m = await _methods.invokeMapMethod<String, dynamic>('getStates') ?? {};
    return m.map((k, v) => MapEntry(k, v == true));
  }

  Future<void> openOverlaySettings() =>
      _methods.invokeMethod('openOverlaySettings');
  Future<void> openAccessibilitySettings() =>
      _methods.invokeMethod('openAccessibilitySettings');

  Future<bool> requestMediaProjection() async =>
      (await _methods.invokeMethod<bool>('requestMediaProjection')) ?? false;

  Future<void> stopCapture() => _methods.invokeMethod('stopCapture');

  Future<void> startFloating() => _methods.invokeMethod('startFloating');
  Future<void> stopFloating() => _methods.invokeMethod('stopFloating');

  Future<void> runScript(String json) =>
      _methods.invokeMethod('runScript', {'json': json});
  Future<void> stopScript() => _methods.invokeMethod('stopScript');

  Future<String?> captureFrame() async =>
      await _methods.invokeMethod<String?>('captureFrame');

  Future<String?> cropTemplate({
    required String path,
    required int l,
    required int t,
    required int r,
    required int b,
  }) async =>
      await _methods.invokeMethod<String?>('cropTemplate', {
        'path': path,
        'l': l, 't': t, 'r': r, 'b': b,
      });

  Future<int?> pickColorAt(int x, int y) async =>
      await _methods.invokeMethod<int?>('pickColorAt', {'x': x, 'y': y});

  /// 启动选点器,color=true 改为选色模式。用户完成选择后通过 events 回传。
  Future<bool> startPicker({bool color = false}) async =>
      (await _methods.invokeMethod<bool>('startPicker', {'color': color})) ?? false;
}

class NativeEvent {
  final String name;
  final Map<String, dynamic> data;
  NativeEvent(this.name, this.data);
}
