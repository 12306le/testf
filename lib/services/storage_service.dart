import 'dart:convert';
import 'package:shared_preferences/shared_preferences.dart';
import '../models/script.dart';

/// 脚本持久化:SharedPreferences 里存 JSON 数组。
class ScriptStorage {
  static const _key = 'scripts_v1';

  Future<List<Script>> loadAll() async {
    final sp = await SharedPreferences.getInstance();
    final raw = sp.getString(_key);
    if (raw == null || raw.isEmpty) return [];
    try {
      final list = jsonDecode(raw) as List;
      return list
          .map((e) => Script.fromJson(Map<String, dynamic>.from(e)))
          .toList();
    } catch (_) {
      return [];
    }
  }

  Future<void> saveAll(List<Script> scripts) async {
    final sp = await SharedPreferences.getInstance();
    await sp.setString(_key, jsonEncode(scripts.map((s) => s.toJson()).toList()));
  }

  Future<void> upsert(Script s) async {
    final all = await loadAll();
    final idx = all.indexWhere((x) => x.id == s.id);
    if (idx >= 0) {
      all[idx] = s;
    } else {
      all.add(s);
    }
    await saveAll(all);
  }

  Future<void> delete(String id) async {
    final all = await loadAll();
    all.removeWhere((x) => x.id == id);
    await saveAll(all);
  }
}
