import 'dart:io';
import 'dart:ui' as ui;
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../app_state.dart';
import '../models/script.dart';
import '../services/native_service.dart';

class ScriptEditorPage extends StatefulWidget {
  final Script script;
  const ScriptEditorPage({super.key, required this.script});

  @override
  State<ScriptEditorPage> createState() => _ScriptEditorPageState();
}

class _ScriptEditorPageState extends State<ScriptEditorPage> {
  late Script s = widget.script;
  final _nameCtl = TextEditingController();
  final _loopCtl = TextEditingController();

  @override
  void initState() {
    super.initState();
    _nameCtl.text = s.name;
    _loopCtl.text = s.loop.toString();
  }

  @override
  void dispose() {
    _nameCtl.dispose();
    _loopCtl.dispose();
    super.dispose();
  }

  void _save() {
    s.name = _nameCtl.text.trim().isEmpty ? '未命名' : _nameCtl.text.trim();
    s.loop = int.tryParse(_loopCtl.text.trim()) ?? 1;
    Navigator.of(context).pop(s);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('编辑脚本'),
        actions: [
          IconButton(icon: const Icon(Icons.check), onPressed: _save, tooltip: '保存'),
        ],
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(12),
            child: Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _nameCtl,
                    decoration: const InputDecoration(
                      labelText: '脚本名',
                      isDense: true,
                      border: OutlineInputBorder(),
                    ),
                  ),
                ),
                const SizedBox(width: 12),
                SizedBox(
                  width: 120,
                  child: TextField(
                    controller: _loopCtl,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(
                      labelText: '循环次数',
                      hintText: '0 = 无限',
                      isDense: true,
                      border: OutlineInputBorder(),
                    ),
                  ),
                ),
              ],
            ),
          ),
          const Divider(height: 1),
          Expanded(
            child: ReorderableListView.builder(
              padding: const EdgeInsets.only(bottom: 80),
              itemCount: s.actions.length,
              onReorder: (oldIdx, newIdx) {
                setState(() {
                  if (newIdx > oldIdx) newIdx--;
                  final item = s.actions.removeAt(oldIdx);
                  s.actions.insert(newIdx, item);
                });
              },
              itemBuilder: (ctx, i) {
                final a = s.actions[i];
                return ListTile(
                  key: ValueKey('a$i'),
                  leading: CircleAvatar(
                    radius: 14,
                    child: Text('${i + 1}', style: const TextStyle(fontSize: 12)),
                  ),
                  title: Text(a.describe()),
                  subtitle: Text(a.type,
                      style: const TextStyle(fontSize: 11, color: Colors.grey)),
                  trailing: Wrap(children: [
                    IconButton(
                      icon: const Icon(Icons.edit_outlined),
                      onPressed: () => _editAction(i),
                    ),
                    IconButton(
                      icon: const Icon(Icons.delete_outline),
                      onPressed: () => setState(() => s.actions.removeAt(i)),
                    ),
                  ]),
                );
              },
            ),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: _addAction,
        icon: const Icon(Icons.add),
        label: const Text('添加动作'),
      ),
    );
  }

  Future<void> _addAction() async {
    final type = await showModalBottomSheet<String>(
      context: context,
      builder: (ctx) => SafeArea(child: Column(mainAxisSize: MainAxisSize.min, children: [
        const ListTile(title: Text('选择动作类型',
            style: TextStyle(fontWeight: FontWeight.bold))),
        _typeTile(ctx, '点击', 'click', Icons.touch_app),
        _typeTile(ctx, '长按', 'longPress', Icons.timer),
        _typeTile(ctx, '滑动', 'swipe', Icons.swipe),
        _typeTile(ctx, '等待', 'sleep', Icons.schedule),
        _typeTile(ctx, '随机等待', 'sleepRandom', Icons.shuffle),
        _typeTile(ctx, '判色', 'checkColor', Icons.colorize),
        _typeTile(ctx, '找色', 'findColor', Icons.color_lens),
        _typeTile(ctx, '找图', 'findImage', Icons.image_search),
        _typeTile(ctx, '找字 (OCR)', 'findText', Icons.text_fields),
        _typeTile(ctx, '循环', 'loop', Icons.repeat),
        _typeTile(ctx, '停止', 'stop', Icons.stop_circle_outlined),
      ])),
    );
    if (type == null) return;
    final action = ScriptAction(type: type, params: _defaults(type));
    setState(() => s.actions.add(action));
    // 立即进入编辑
    await _editAction(s.actions.length - 1);
  }

  Widget _typeTile(BuildContext ctx, String label, String value, IconData icon) =>
      ListTile(
        leading: Icon(icon),
        title: Text(label),
        onTap: () => Navigator.pop(ctx, value),
      );

  Map<String, dynamic> _defaults(String type) {
    switch (type) {
      case 'click':     return {'x': 100, 'y': 100, 'duration': 30};
      case 'longPress': return {'x': 100, 'y': 100, 'duration': 800};
      case 'swipe':     return {'x1': 100, 'y1': 500, 'x2': 100, 'y2': 100, 'duration': 300};
      case 'sleep':     return {'ms': 500};
      case 'sleepRandom': return {'min': 300, 'max': 800};
      case 'checkColor': return {'x': 100, 'y': 100, 'argb': 0xFF00FF00, 'tolerance': 10};
      case 'findColor':  return {'argb': 0xFF00FF00, 'tolerance': 10, 'clickOnFound': true};
      case 'findImage':  return {'path': '', 'threshold': 0.9, 'clickOnFound': true};
      case 'findText':   return {'text': '', 'contains': true, 'clickOnFound': true};
      case 'loop':       return {'times': 3, 'actions': <Map<String, dynamic>>[]};
      default: return {};
    }
  }

  Future<void> _editAction(int idx) async {
    final a = s.actions[idx];
    final updated = await showDialog<ScriptAction>(
      context: context,
      builder: (_) => ActionEditDialog(action: a),
    );
    if (updated != null) setState(() => s.actions[idx] = updated);
  }
}

/// 编辑单个动作参数
class ActionEditDialog extends StatefulWidget {
  final ScriptAction action;
  const ActionEditDialog({super.key, required this.action});

  @override
  State<ActionEditDialog> createState() => _ActionEditDialogState();
}

class _ActionEditDialogState extends State<ActionEditDialog> {
  late final Map<String, dynamic> p = Map<String, dynamic>.from(widget.action.params);
  late final String type = widget.action.type;

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text('编辑: $type'),
      content: SingleChildScrollView(child: _body()),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('取消')),
        TextButton(
          onPressed: () => Navigator.pop(
            context, ScriptAction(type: type, params: p)),
          child: const Text('确定'),
        ),
      ],
    );
  }

  Widget _body() {
    switch (type) {
      case 'click':
      case 'longPress':
        return Column(mainAxisSize: MainAxisSize.min, children: [
          _pointPicker(),
          _numField('x', 'x'),
          _numField('y', 'y'),
          _numField('duration', '时长(ms)'),
        ]);
      case 'swipe':
        return Column(mainAxisSize: MainAxisSize.min, children: [
          _pickerRow('拾取起点', () async => _pickPoint('x1', 'y1')),
          _numField('x1', 'x1'), _numField('y1', 'y1'),
          _pickerRow('拾取终点', () async => _pickPoint('x2', 'y2')),
          _numField('x2', 'x2'), _numField('y2', 'y2'),
          _numField('duration', '时长(ms)'),
        ]);
      case 'sleep':
        return _numField('ms', '等待(ms)');
      case 'sleepRandom':
        return Column(mainAxisSize: MainAxisSize.min, children: [
          _numField('min', '最小(ms)'),
          _numField('max', '最大(ms)'),
        ]);
      case 'checkColor':
        return Column(mainAxisSize: MainAxisSize.min, children: [
          _colorPickerRow(),
          _numField('x', 'x'), _numField('y', 'y'),
          _argbField(),
          _numField('tolerance', '容差'),
        ]);
      case 'findColor':
        return Column(mainAxisSize: MainAxisSize.min, children: [
          _colorPickerRow(),
          _argbField(),
          _numField('tolerance', '容差'),
          _boolField('clickOnFound', '命中后点击'),
          _roiField(),
        ]);
      case 'findImage':
        return Column(mainAxisSize: MainAxisSize.min, children: [
          _templatePickerRow(),
          _pathField(),
          _numField('threshold', '阈值 0~1', isDouble: true),
          _boolField('clickOnFound', '命中后点击'),
          _roiField(),
        ]);
      case 'findText':
        return Column(mainAxisSize: MainAxisSize.min, children: [
          TextFormField(
            initialValue: (p['text'] ?? '').toString(),
            decoration: const InputDecoration(
              labelText: '要找的文本',
              hintText: '例如: 开始 / 确定 / Login',
              isDense: true,
            ),
            onChanged: (v) => p['text'] = v,
          ),
          const SizedBox(height: 8),
          _boolField('contains', '包含匹配 (关则精确相等)'),
          _boolField('clickOnFound', '命中后点击'),
          _roiField(),
          const Padding(
            padding: EdgeInsets.only(top: 8),
            child: Text('提示:首次运行需要加载 OCR 模型,约 1-2 秒',
                style: TextStyle(fontSize: 12, color: Colors.grey)),
          ),
        ]);
      case 'loop':
        return Column(mainAxisSize: MainAxisSize.min, children: [
          _numField('times', '循环次数'),
          const Padding(
            padding: EdgeInsets.only(top: 8),
            child: Text('子步骤需在保存后进入该节点,再用外层编辑器添加',
                style: TextStyle(fontSize: 12, color: Colors.grey)),
          ),
        ]);
      default:
        return const Text('无参数');
    }
  }

  Widget _numField(String key, String label, {bool isDouble = false}) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: TextFormField(
        initialValue: (p[key] ?? '').toString(),
        decoration: InputDecoration(labelText: label, isDense: true),
        keyboardType: const TextInputType.numberWithOptions(decimal: true, signed: true),
        onChanged: (v) {
          p[key] = isDouble ? (double.tryParse(v) ?? 0) : (int.tryParse(v) ?? 0);
        },
      ),
    );
  }

  Widget _boolField(String key, String label) => SwitchListTile(
        contentPadding: EdgeInsets.zero,
        title: Text(label),
        value: p[key] == true,
        onChanged: (v) => setState(() => p[key] = v),
      );

  Widget _argbField() {
    final cur = (p['argb'] as num?)?.toInt() ?? 0xFF00FF00;
    return Row(children: [
      Expanded(child: TextFormField(
        initialValue: '#${cur.toUnsigned(32).toRadixString(16).padLeft(8, '0').toUpperCase()}',
        decoration: const InputDecoration(labelText: 'ARGB 颜色 (如 #FF00FF00)', isDense: true),
        onChanged: (v) {
          final hex = v.replaceAll('#', '');
          final parsed = int.tryParse(hex, radix: 16);
          if (parsed != null) p['argb'] = parsed.toSigned(32);
        },
      )),
      const SizedBox(width: 8),
      Container(width: 28, height: 28, decoration: BoxDecoration(
        color: Color(cur),
        border: Border.all(color: Colors.black26),
      )),
    ]);
  }

  Widget _pathField() {
    return TextFormField(
      initialValue: (p['path'] ?? '').toString(),
      decoration: const InputDecoration(labelText: '模板图片路径', isDense: true),
      onChanged: (v) => p['path'] = v,
    );
  }

  Widget _roiField() {
    final r = (p['roi'] as List?)?.map((e) => (e as num).toInt()).toList();
    final txt = r == null ? '' : r.join(',');
    return TextFormField(
      initialValue: txt,
      decoration: const InputDecoration(
        labelText: 'ROI (left,top,right,bottom,留空=全屏)',
        isDense: true,
      ),
      onChanged: (v) {
        if (v.trim().isEmpty) { p.remove('roi'); return; }
        final parts = v.split(',').map((s) => int.tryParse(s.trim())).toList();
        if (parts.length == 4 && parts.every((e) => e != null)) {
          p['roi'] = parts.map((e) => e!).toList();
        }
      },
    );
  }

  Widget _pointPicker() => _pickerRow('拾取坐标', () => _pickPoint('x', 'y'));

  Widget _pickerRow(String label, Future<void> Function() onTap) => Align(
    alignment: Alignment.centerLeft,
    child: TextButton.icon(
      onPressed: () async { await onTap(); if (mounted) setState(() {}); },
      icon: const Icon(Icons.my_location),
      label: Text(label),
    ),
  );

  Future<void> _pickPoint(String xKey, String yKey) async {
    final app = context.read<AppState>();
    final native = NativeService.instance;
    if (!app.overlay) {
      await native.openOverlaySettings();
      return;
    }
    if (!app.floating) await native.startFloating();
    final ok = await native.startPicker(color: false);
    if (!ok) return;
    final res = await app.awaitPointPicker();
    if (res != null && res['x'] != null) {
      p[xKey] = res['x']; p[yKey] = res['y'];
    }
  }

  Future<void> _launchPicker({required bool color}) async {
    // 保留给色点流程的提示
  }

  Widget _colorPickerRow() => _pickerRow('拾取颜色', () async {
    final app = context.read<AppState>();
    final native = NativeService.instance;
    if (!app.overlay) { await native.openOverlaySettings(); return; }
    if (!app.capture) {
      final ok = await native.requestMediaProjection();
      if (!ok) return;
    }
    if (!app.floating) await native.startFloating();
    final ok = await native.startPicker(color: true);
    if (!ok) return;
    final r = await app.awaitColorPicker();
    if (r != null && r['argb'] != null) {
      p['argb'] = (r['argb'] as num).toInt();
      if (p.containsKey('x') && r['x'] != null) {
        p['x'] = r['x']; p['y'] = r['y'];
      }
    }
  });

  Widget _templatePickerRow() => _pickerRow('截屏并裁剪模板', () async {
    final app = context.read<AppState>();
    final native = NativeService.instance;
    if (!app.capture) {
      final ok = await native.requestMediaProjection();
      if (!ok) return;
    }
    final framePath = await native.captureFrame();
    if (framePath == null || !mounted) return;
    final saved = await Navigator.of(context).push<String>(
      MaterialPageRoute(builder: (_) => _TemplateCropPage(framePath: framePath)),
    );
    if (saved != null) p['path'] = saved;
  });
}

// ---------------- 裁剪页 ----------------
class _TemplateCropPage extends StatefulWidget {
  final String framePath;
  const _TemplateCropPage({required this.framePath});
  @override
  State<_TemplateCropPage> createState() => _TemplateCropPageState();
}

class _TemplateCropPageState extends State<_TemplateCropPage> {
  Rect? _rect;
  Offset? _start;
  final _key = GlobalKey();

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('裁剪模板'),
        actions: [
          IconButton(
            icon: const Icon(Icons.check),
            onPressed: () async {
              final r = _rect;
              if (r == null) { Navigator.pop(context); return; }
              final box = _key.currentContext?.findRenderObject() as RenderBox?;
              if (box == null) { Navigator.pop(context); return; }
              // 把屏幕坐标映射到原图坐标
              final img = await _loadImageSize(widget.framePath);
              final shown = box.size;
              final sx = img.width / shown.width;
              final sy = img.height / shown.height;
              final l = (r.left * sx).toInt();
              final t = (r.top * sy).toInt();
              final rr = (r.right * sx).toInt();
              final bb = (r.bottom * sy).toInt();
              final path = await NativeService.instance.cropTemplate(
                path: widget.framePath, l: l, t: t, r: rr, b: bb);
              if (mounted) Navigator.pop(context, path);
            },
          ),
        ],
      ),
      body: GestureDetector(
        onPanStart: (d) => setState(() { _start = d.localPosition; _rect = null; }),
        onPanUpdate: (d) => setState(() {
          final s = _start;
          if (s == null) return;
          _rect = Rect.fromPoints(s, d.localPosition);
        }),
        child: Stack(children: [
          Center(child: Image.file(
            File(widget.framePath),
            key: _key,
            fit: BoxFit.contain,
          )),
          if (_rect != null) Positioned.fromRect(
            rect: _rect!,
            child: Container(
              decoration: BoxDecoration(
                border: Border.all(color: Colors.redAccent, width: 2),
                color: Colors.red.withOpacity(0.1),
              ),
            ),
          ),
        ]),
      ),
    );
  }

  Future<Size> _loadImageSize(String path) async {
    final bytes = await File(path).readAsBytes();
    final codec = await ui.instantiateImageCodec(bytes);
    final frame = await codec.getNextFrame();
    return Size(frame.image.width.toDouble(), frame.image.height.toDouble());
  }
}
