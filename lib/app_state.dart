import 'dart:async';
import 'package:flutter/foundation.dart';
import 'models/script.dart';
import 'services/native_service.dart';
import 'services/storage_service.dart';

/// 全局状态:权限、脚本列表、运行日志。
class AppState extends ChangeNotifier {
  final _storage = ScriptStorage();
  final _native = NativeService.instance;

  bool overlay = false;
  bool accessibility = false;
  bool capture = false;
  bool floating = false;
  bool running = false;

  List<Script> scripts = [];

  final List<String> logs = [];
  static const int _maxLogs = 400;

  /// 坐标/颜色拾取的一次性等待者
  Completer<Map<String, dynamic>>? _pickerWaiter;
  String? _pickerExpected; // "point" or "color"

  AppState() {
    _native.ensureListening();
    _native.events.listen(_onEvent);
    _refreshAll();
  }

  Future<void> _refreshAll() async {
    await refreshStates();
    scripts = await _storage.loadAll();
    notifyListeners();
  }

  Future<void> refreshStates() async {
    final s = await _native.getStates();
    overlay = s['overlay'] ?? false;
    accessibility = s['accessibility'] ?? false;
    capture = s['capture'] ?? false;
    floating = s['floating'] ?? false;
    running = s['running'] ?? false;
    notifyListeners();
  }

  void _onEvent(NativeEvent e) {
    switch (e.name) {
      case 'state.accessibility':
        accessibility = e.data['enabled'] == true; break;
      case 'state.capture':
        capture = e.data['running'] == true; break;
      case 'runner.state':
        running = e.data['running'] == true; break;
      case 'runner.log':
        final line = e.data['line']?.toString() ?? '';
        if (line.isNotEmpty) {
          logs.add(line);
          if (logs.length > _maxLogs) logs.removeRange(0, logs.length - _maxLogs);
        }
        break;
      case 'picker.point':
        if (_pickerExpected == 'point') {
          _pickerWaiter?.complete(Map<String, dynamic>.from(e.data));
          _pickerWaiter = null; _pickerExpected = null;
        }
        break;
      case 'picker.color':
        if (_pickerExpected == 'color') {
          _pickerWaiter?.complete(Map<String, dynamic>.from(e.data));
          _pickerWaiter = null; _pickerExpected = null;
        }
        break;
      case 'picker.color.failed':
        if (_pickerExpected == 'color') {
          _pickerWaiter?.complete({});
          _pickerWaiter = null; _pickerExpected = null;
        }
        break;
      case 'floating.run':
        // 悬浮窗点"运行":若当前有脚本则跑第一个,这里简化为广播提示
        break;
      case 'floating.stop':
        stopScript();
        break;
    }
    notifyListeners();
  }

  /// 等悬浮拾取器回一个坐标 (x, y)
  Future<Map<String, dynamic>?> awaitPointPicker() async {
    _pickerWaiter?.complete({});
    _pickerWaiter = Completer<Map<String, dynamic>>();
    _pickerExpected = 'point';
    return _pickerWaiter!.future.timeout(const Duration(minutes: 5),
        onTimeout: () { _pickerExpected = null; return {}; });
  }

  Future<Map<String, dynamic>?> awaitColorPicker() async {
    _pickerWaiter?.complete({});
    _pickerWaiter = Completer<Map<String, dynamic>>();
    _pickerExpected = 'color';
    return _pickerWaiter!.future.timeout(const Duration(minutes: 5),
        onTimeout: () { _pickerExpected = null; return {}; });
  }

  // ---- CRUD ----
  Future<void> saveScript(Script s) async {
    await _storage.upsert(s);
    scripts = await _storage.loadAll();
    notifyListeners();
  }

  Future<void> deleteScript(String id) async {
    await _storage.delete(id);
    scripts = await _storage.loadAll();
    notifyListeners();
  }

  Future<void> runScript(Script s) async {
    logs.clear();
    await _native.runScript(s.toEngineJson());
  }

  Future<void> stopScript() => _native.stopScript();
}
