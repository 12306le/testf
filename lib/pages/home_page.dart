import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../app_state.dart';
import '../models/script.dart';
import '../services/native_service.dart';
import 'script_editor_page.dart';

class HomePage extends StatelessWidget {
  const HomePage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('自动点击器'),
        actions: [
          Consumer<AppState>(builder: (_, s, __) => IconButton(
            onPressed: () => s.refreshStates(),
            icon: const Icon(Icons.refresh),
            tooltip: '刷新状态',
          )),
        ],
      ),
      body: Column(
        children: const [
          _PermissionPanel(),
          Divider(height: 1),
          Expanded(child: _ScriptList()),
          _RunLogPanel(),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () async {
          final created = await Navigator.of(context).push<Script>(
            MaterialPageRoute(builder: (_) =>
              ScriptEditorPage(script: Script(name: '新脚本'))),
          );
          if (created != null && context.mounted) {
            await context.read<AppState>().saveScript(created);
          }
        },
        icon: const Icon(Icons.add),
        label: const Text('新建脚本'),
      ),
    );
  }
}

class _PermissionPanel extends StatelessWidget {
  const _PermissionPanel();

  @override
  Widget build(BuildContext context) {
    final s = context.watch<AppState>();
    final native = NativeService.instance;
    Widget row(String label, bool ok, VoidCallback action, {String? btn}) => Row(
      children: [
        Icon(ok ? Icons.check_circle : Icons.error_outline,
             color: ok ? Colors.green : Colors.orange, size: 20),
        const SizedBox(width: 8),
        Expanded(child: Text(label)),
        TextButton(onPressed: action, child: Text(btn ?? (ok ? '已授权' : '去授权'))),
      ],
    );
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 4),
      child: Column(
        children: [
          row('悬浮窗权限', s.overlay,
              () async { await native.openOverlaySettings(); }),
          row('无障碍服务', s.accessibility,
              () async { await native.openAccessibilitySettings(); }),
          row('屏幕捕获 (图色)', s.capture,
              () async {
                if (s.capture) { await native.stopCapture(); }
                else { await native.requestMediaProjection(); }
                await s.refreshStates();
              }, btn: s.capture ? '关闭' : '去授权'),
          Row(
            children: [
              const Icon(Icons.dashboard_outlined, size: 20),
              const SizedBox(width: 8),
              const Expanded(child: Text('悬浮控制面板')),
              TextButton(
                onPressed: () async {
                  if (s.floating) { await native.stopFloating(); }
                  else { await native.startFloating(); }
                  await s.refreshStates();
                },
                child: Text(s.floating ? '关闭' : '开启'),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _ScriptList extends StatelessWidget {
  const _ScriptList();

  @override
  Widget build(BuildContext context) {
    final s = context.watch<AppState>();
    if (s.scripts.isEmpty) {
      return const Center(
        child: Text('还没有脚本\n点右下角"新建脚本"开始',
          textAlign: TextAlign.center,
          style: TextStyle(color: Colors.grey),
        ),
      );
    }
    return ListView.separated(
      itemCount: s.scripts.length,
      separatorBuilder: (_, __) => const Divider(height: 1),
      itemBuilder: (ctx, i) {
        final sc = s.scripts[i];
        return ListTile(
          title: Text(sc.name),
          subtitle: Text('${sc.actions.length} 步  ·  循环 ${sc.loop == 0 ? "∞" : sc.loop}'),
          leading: const Icon(Icons.description_outlined),
          onTap: () async {
            final edited = await Navigator.of(ctx).push<Script>(
              MaterialPageRoute(builder: (_) => ScriptEditorPage(script: sc.copy())),
            );
            if (edited != null && ctx.mounted) {
              await ctx.read<AppState>().saveScript(edited);
            }
          },
          trailing: Wrap(
            spacing: 4,
            children: [
              IconButton(
                icon: Icon(s.running ? Icons.stop_circle : Icons.play_circle_fill,
                           color: s.running ? Colors.red : Colors.green),
                tooltip: s.running ? '停止' : '运行',
                onPressed: () {
                  if (s.running) { ctx.read<AppState>().stopScript(); }
                  else { ctx.read<AppState>().runScript(sc); }
                },
              ),
              IconButton(
                icon: const Icon(Icons.delete_outline),
                onPressed: () async {
                  final ok = await showDialog<bool>(
                    context: ctx,
                    builder: (_) => AlertDialog(
                      title: const Text('删除脚本?'),
                      content: Text('确定删除 "${sc.name}"?'),
                      actions: [
                        TextButton(onPressed: () => Navigator.pop(ctx, false),
                                   child: const Text('取消')),
                        TextButton(onPressed: () => Navigator.pop(ctx, true),
                                   child: const Text('删除')),
                      ],
                    ),
                  );
                  if (ok == true && ctx.mounted) {
                    await ctx.read<AppState>().deleteScript(sc.id);
                  }
                },
              ),
            ],
          ),
        );
      },
    );
  }
}

class _RunLogPanel extends StatelessWidget {
  const _RunLogPanel();
  @override
  Widget build(BuildContext context) {
    final s = context.watch<AppState>();
    if (s.logs.isEmpty && !s.running) return const SizedBox.shrink();
    return Container(
      height: 140,
      color: Colors.black87,
      padding: const EdgeInsets.all(8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            Icon(s.running ? Icons.circle : Icons.circle_outlined,
                 size: 10, color: s.running ? Colors.greenAccent : Colors.grey),
            const SizedBox(width: 6),
            Text(s.running ? '运行中' : '就绪',
                 style: const TextStyle(color: Colors.white70, fontSize: 12)),
            const Spacer(),
            TextButton(
              onPressed: s.running ? () => s.stopScript() : null,
              child: const Text('停止', style: TextStyle(color: Colors.redAccent)),
            ),
          ]),
          Expanded(
            child: ListView.builder(
              reverse: true,
              itemCount: s.logs.length,
              itemBuilder: (_, i) => Text(
                s.logs[s.logs.length - 1 - i],
                style: const TextStyle(
                  color: Colors.white, fontSize: 12, fontFamily: 'monospace'),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
