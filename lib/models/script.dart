import 'dart:convert';
import 'package:uuid/uuid.dart';

/// 单个动作。保持扁平的 Map,便于序列化为 JSON 给原生执行。
class ScriptAction {
  String type;
  Map<String, dynamic> params;

  ScriptAction({required this.type, Map<String, dynamic>? params})
      : params = params ?? {};

  Map<String, dynamic> toJson() => {'type': type, ...params};

  factory ScriptAction.fromJson(Map<String, dynamic> j) {
    final copy = Map<String, dynamic>.from(j);
    final t = copy.remove('type') as String? ?? 'sleep';
    return ScriptAction(type: t, params: copy);
  }

  String describe() {
    switch (type) {
      case 'click':
        return '点击 (${params['x']}, ${params['y']})';
      case 'longPress':
        return '长按 (${params['x']}, ${params['y']}) ${params['duration'] ?? 600}ms';
      case 'swipe':
        return '滑动 (${params['x1']},${params['y1']})→(${params['x2']},${params['y2']})';
      case 'sleep':
        return '等待 ${params['ms']}ms';
      case 'sleepRandom':
        return '随机等待 ${params['min']}-${params['max']}ms';
      case 'checkColor':
        return '判色 (${params['x']},${params['y']}) ${_colorHex(params['argb'])}';
      case 'findColor':
        return '找色 ${_colorHex(params['argb'])}'
            '${params['clickOnFound'] == true ? ' 并点击' : ''}';
      case 'findImage':
        return '找图 阈值 ${params['threshold'] ?? 0.9}'
            '${params['clickOnFound'] == true ? ' 并点击' : ''}';
      case 'findText':
        return '找字 "${params['text']}"'
            '${params['contains'] == false ? ' (精确)' : ''}'
            '${params['clickOnFound'] == true ? ' 并点击' : ''}';
      case 'loop':
        final n = (params['actions'] as List?)?.length ?? 0;
        return '循环 ${params['times']}次 · $n 个子步骤';
      case 'stop':
        return '停止';
      default:
        return type;
    }
  }

  static String _colorHex(dynamic argb) {
    if (argb is int) {
      return '#${argb.toUnsigned(32).toRadixString(16).padLeft(8, '0').toUpperCase()}';
    }
    return '';
  }
}

class Script {
  final String id;
  String name;
  int loop; // 0 = 无限
  List<ScriptAction> actions;

  Script({
    String? id,
    required this.name,
    this.loop = 1,
    List<ScriptAction>? actions,
  })  : id = id ?? const Uuid().v4(),
        actions = actions ?? [];

  Map<String, dynamic> toJson() => {
        'id': id,
        'name': name,
        'loop': loop,
        'actions': actions.map((a) => a.toJson()).toList(),
      };

  String toEngineJson() => jsonEncode({
        'name': name,
        'loop': loop,
        'actions': actions.map((a) => a.toJson()).toList(),
      });

  factory Script.fromJson(Map<String, dynamic> j) => Script(
        id: j['id'] as String?,
        name: j['name'] as String? ?? '未命名',
        loop: (j['loop'] as num?)?.toInt() ?? 1,
        actions: (j['actions'] as List? ?? [])
            .map((e) => ScriptAction.fromJson(Map<String, dynamic>.from(e)))
            .toList(),
      );

  Script copy() => Script.fromJson(jsonDecode(jsonEncode(toJson())));
}
