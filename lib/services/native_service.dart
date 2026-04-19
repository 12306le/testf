import 'dart:async';
import 'dart:convert';
import 'package:flutter/services.dart';

class OcrLine {
  final String text;
  final double score;
  final List<int> box; // 8 ints: x0,y0,x1,y1,x2,y2,x3,y3
  OcrLine({required this.text, required this.score, required this.box});

  int get minX => [box[0], box[2], box[4], box[6]].reduce((a, b) => a < b ? a : b);
  int get minY => [box[1], box[3], box[5], box[7]].reduce((a, b) => a < b ? a : b);
  int get maxX => [box[0], box[2], box[4], box[6]].reduce((a, b) => a > b ? a : b);
  int get maxY => [box[1], box[3], box[5], box[7]].reduce((a, b) => a > b ? a : b);
  int get centerX => (minX + maxX) ~/ 2;
  int get centerY => (minY + maxY) ~/ 2;
}

class OcrOutcome {
  final int elapsedMs;
  final List<OcrLine> lines;
  OcrOutcome({required this.elapsedMs, required this.lines});
}

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
        'path': path, 'l': l, 't': t, 'r': r, 'b': b,
      });

  Future<int?> pickColorAt(int x, int y) async =>
      await _methods.invokeMethod<int?>('pickColorAt', {'x': x, 'y': y});

  Future<bool> startPicker({bool color = false}) async =>
      (await _methods.invokeMethod<bool>('startPicker', {'color': color})) ?? false;

  // ---------------- OCR ----------------
  Future<void> initOcr() => _methods.invokeMethod('initOcr');

  Future<OcrOutcome?> ocrRecognizeFile(String path, {List<int>? roi}) async {
    final r = await _methods.invokeMapMethod<String, dynamic>(
      'ocrRecognizeFile', {'path': path, if (roi != null) 'roi': roi});
    return _parseOcr(r);
  }

  Future<OcrOutcome?> ocrRecognizeFrame({List<int>? roi}) async {
    final r = await _methods.invokeMapMethod<String, dynamic>(
      'ocrRecognizeFrame', {if (roi != null) 'roi': roi});
    return _parseOcr(r);
  }

  OcrOutcome? _parseOcr(Map<String, dynamic>? m) {
    if (m == null) return null;
    final lines = (m['lines'] as List? ?? []).map<OcrLine>((e) {
      final mm = Map<String, dynamic>.from(e as Map);
      return OcrLine(
        text: (mm['text'] ?? '').toString(),
        score: (mm['score'] as num?)?.toDouble() ?? 0.0,
        box: ((mm['box'] as List?) ?? const [])
            .map((x) => (x as num).toInt()).toList(),
      );
    }).toList();
    return OcrOutcome(
      elapsedMs: (m['elapsedMs'] as num?)?.toInt() ?? 0,
      lines: lines,
    );
  }
}

class NativeEvent {
  final String name;
  final Map<String, dynamic> data;
  NativeEvent(this.name, this.data);
}
