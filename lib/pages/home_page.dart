import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../app_state.dart';
import '../models/script.dart';
import '../services/native_service.dart';
import 'script_editor_page.dart';
import 'ocr_test_page.dart';

class HomePage extends StatelessWidget {
  const HomePage({super.key});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Scaffold(
      body: SafeArea(
        child: Column(
          children: [
            const _Header(),
            const _PermissionGrid(),
            const SizedBox(height: 4),
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 20, 20, 8),
              child: Row(
                children: [
                  Text('脚本', style: TextStyle(
                    fontSize: 18, fontWeight: FontWeight.w700, color: cs.onSurface)),
                  const SizedBox(width: 10),
                  Consumer<AppState>(builder: (_, s, __) => Container(
                    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 2),
                    decoration: BoxDecoration(
                      color: cs.primaryContainer,
                      borderRadius: BorderRadius.circular(10),
                    ),
                    child: Text('${s.scripts.length}',
                      style: TextStyle(color: cs.onPrimaryContainer,
                        fontWeight: FontWeight.w600, fontSize: 12)),
                  )),
                  const Spacer(),
                  TextButton.icon(
                    onPressed: () => Navigator.push(context,
                      MaterialPageRoute(builder: (_) => const OcrTestPage())),
                    icon: const Icon(Icons.text_fields_rounded, size: 18),
                    label: const Text('OCR 测试'),
                  ),
                ],
              ),
            ),
            const Expanded(child: _ScriptList()),
            const _RunLogPanel(),
          ],
        ),
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
        icon: const Icon(Icons.add_rounded),
        label: const Text('新建脚本'),
      ),
    );
  }
}

class _Header extends StatelessWidget {
  const _Header();
  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(20, 14, 20, 20),
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft, end: Alignment.bottomRight,
          colors: [cs.primary, cs.primary.withOpacity(0.7), cs.secondary],
        ),
        borderRadius: const BorderRadius.only(
          bottomLeft: Radius.circular(28),
          bottomRight: Radius.circular(28),
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            const Icon(Icons.touch_app_rounded, color: Colors.white, size: 26),
            const SizedBox(width: 10),
            const Text('自动点击器',
              style: TextStyle(fontSize: 22, fontWeight: FontWeight.w800,
                color: Colors.white, letterSpacing: 0.5)),
            const Spacer(),
            Consumer<AppState>(builder: (_, s, __) => IconButton(
              onPressed: () => s.refreshStates(),
              icon: const Icon(Icons.refresh_rounded, color: Colors.white),
              tooltip: '刷新状态',
            )),
          ]),
          const SizedBox(height: 4),
          const Text('找色 · 找图 · 找字 · 循环执行',
            style: TextStyle(color: Colors.white70, fontSize: 13)),
        ],
      ),
    );
  }
}

class _PermissionGrid extends StatelessWidget {
  const _PermissionGrid();

  @override
  Widget build(BuildContext context) {
    final s = context.watch<AppState>();
    final native = NativeService.instance;
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 16, 12, 8),
      child: GridView.count(
        crossAxisCount: 2,
        shrinkWrap: true,
        physics: const NeverScrollableScrollPhysics(),
        mainAxisSpacing: 10,
        crossAxisSpacing: 10,
        childAspectRatio: 2.8,
        children: [
          _PermCard(
            icon: Icons.dashboard_customize_rounded,
            label: '悬浮窗',
            ok: s.overlay,
            badge: s.overlay ? (s.floating ? '面板开启' : '已授权') : '去授权',
            onTap: () async {
              if (!s.overlay) { await native.openOverlaySettings(); return; }
              if (s.floating) { await native.stopFloating(); }
              else { await native.startFloating(); }
              await s.refreshStates();
            },
          ),
          _PermCard(
            icon: Icons.accessibility_new_rounded,
            label: '无障碍',
            ok: s.accessibility,
            badge: s.accessibility ? '已启用' : '去授权',
            onTap: () async { await native.openAccessibilitySettings(); },
          ),
          _PermCard(
            icon: Icons.photo_camera_back_rounded,
            label: '屏幕捕获',
            ok: s.capture,
            badge: s.capture ? '运行中' : '去授权',
            onTap: () async {
              if (s.capture) { await native.stopCapture(); }
              else { await native.requestMediaProjection(); }
              await s.refreshStates();
            },
          ),
          _PermCard(
            icon: Icons.text_fields_rounded,
            label: 'OCR 模型',
            ok: s.ocr,
            badge: s.ocr ? '就绪' : (s.ocrLoading ? '加载中' : '点击加载'),
            busy: s.ocrLoading,
            onTap: () { if (!s.ocr && !s.ocrLoading) native.initOcr(); },
          ),
        ],
      ),
    );
  }
}

class _PermCard extends StatelessWidget {
  final IconData icon;
  final String label;
  final String badge;
  final bool ok;
  final bool busy;
  final VoidCallback onTap;
  const _PermCard({required this.icon, required this.label,
      required this.badge, required this.ok, this.busy = false, required this.onTap});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final okColor = Colors.green.shade600;
    final tint = ok ? okColor.withOpacity(0.12) : cs.surfaceContainerHigh;
    return Material(
      color: tint,
      borderRadius: BorderRadius.circular(16),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(16),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
          child: Row(children: [
            Container(
              width: 36, height: 36,
              decoration: BoxDecoration(
                color: ok ? okColor.withOpacity(0.2) : cs.primary.withOpacity(0.12),
                borderRadius: BorderRadius.circular(10),
              ),
              child: busy
                ? Padding(
                    padding: const EdgeInsets.all(8),
                    child: CircularProgressIndicator(
                      strokeWidth: 2, color: cs.primary),
                  )
                : Icon(icon, color: ok ? okColor : cs.primary, size: 20),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(label, style: const TextStyle(
                    fontSize: 13, fontWeight: FontWeight.w700)),
                  Text(badge, maxLines: 1, overflow: TextOverflow.ellipsis,
                    style: TextStyle(fontSize: 11,
                      color: ok ? okColor : cs.onSurfaceVariant)),
                ],
              ),
            ),
          ]),
        ),
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
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.inbox_rounded, size: 56,
              color: Theme.of(context).colorScheme.outline),
            const SizedBox(height: 12),
            Text('还没有脚本', style: TextStyle(
              fontSize: 15, color: Theme.of(context).colorScheme.outline)),
            const SizedBox(height: 4),
            Text('点右下角"新建脚本"开始',
              style: TextStyle(fontSize: 12,
                color: Theme.of(context).colorScheme.outline)),
          ],
        ),
      );
    }
    return ListView.separated(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 90),
      itemCount: s.scripts.length,
      separatorBuilder: (_, __) => const SizedBox(height: 10),
      itemBuilder: (ctx, i) => _ScriptCard(script: s.scripts[i]),
    );
  }
}

class _ScriptCard extends StatelessWidget {
  final Script script;
  const _ScriptCard({required this.script});

  @override
  Widget build(BuildContext ctx) {
    final s = ctx.watch<AppState>();
    final cs = Theme.of(ctx).colorScheme;
    final actionCount = script.actions.length;
    final loopText = script.loop == 0 ? '无限' : '${script.loop} 次';
    return Material(
      color: cs.surfaceContainerHigh,
      borderRadius: BorderRadius.circular(18),
      child: InkWell(
        borderRadius: BorderRadius.circular(18),
        onTap: () async {
          final edited = await Navigator.of(ctx).push<Script>(
            MaterialPageRoute(builder: (_) => ScriptEditorPage(script: script.copy())),
          );
          if (edited != null && ctx.mounted) {
            await ctx.read<AppState>().saveScript(edited);
          }
        },
        child: Padding(
          padding: const EdgeInsets.fromLTRB(14, 12, 10, 12),
          child: Row(
            children: [
              Container(
                width: 44, height: 44,
                decoration: BoxDecoration(
                  color: cs.primaryContainer,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Icon(Icons.auto_mode_rounded,
                  color: cs.onPrimaryContainer),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(script.name, style: const TextStyle(
                      fontSize: 15, fontWeight: FontWeight.w700)),
                    const SizedBox(height: 4),
                    Row(children: [
                      _tag(ctx, '$actionCount 步'),
                      const SizedBox(width: 6),
                      _tag(ctx, '循环 $loopText'),
                    ]),
                  ],
                ),
              ),
              IconButton(
                icon: Icon(s.running
                  ? Icons.stop_circle_rounded
                  : Icons.play_circle_rounded,
                  color: s.running ? Colors.red.shade600 : Colors.green.shade600,
                  size: 30),
                onPressed: () {
                  if (s.running) { ctx.read<AppState>().stopScript(); }
                  else { ctx.read<AppState>().runScript(script); }
                },
              ),
              PopupMenuButton<String>(
                icon: const Icon(Icons.more_vert_rounded),
                onSelected: (v) async {
                  if (v == 'delete') {
                    final ok = await showDialog<bool>(
                      context: ctx,
                      builder: (_) => AlertDialog(
                        title: const Text('删除脚本?'),
                        content: Text('确定删除 "${script.name}"?'),
                        actions: [
                          TextButton(onPressed: () => Navigator.pop(ctx, false),
                            child: const Text('取消')),
                          FilledButton(onPressed: () => Navigator.pop(ctx, true),
                            child: const Text('删除')),
                        ],
                      ),
                    );
                    if (ok == true && ctx.mounted) {
                      await ctx.read<AppState>().deleteScript(script.id);
                    }
                  }
                },
                itemBuilder: (_) => const [
                  PopupMenuItem(value: 'delete',
                    child: Row(children: [
                      Icon(Icons.delete_outline_rounded, size: 18),
                      SizedBox(width: 8), Text('删除'),
                    ])),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _tag(BuildContext ctx, String text) {
    final cs = Theme.of(ctx).colorScheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(
        color: cs.surfaceContainerLow,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Text(text, style: TextStyle(
        fontSize: 11, color: cs.onSurfaceVariant)),
    );
  }
}

class _RunLogPanel extends StatefulWidget {
  const _RunLogPanel();
  @override
  State<_RunLogPanel> createState() => _RunLogPanelState();
}

class _RunLogPanelState extends State<_RunLogPanel> {
  bool _expanded = false;
  @override
  Widget build(BuildContext context) {
    final s = context.watch<AppState>();
    final cs = Theme.of(context).colorScheme;
    if (s.logs.isEmpty && !s.running) return const SizedBox.shrink();
    return AnimatedContainer(
      duration: const Duration(milliseconds: 250),
      height: _expanded ? 220 : 46,
      decoration: BoxDecoration(
        color: cs.surfaceContainerHighest,
        borderRadius: const BorderRadius.only(
          topLeft: Radius.circular(20),
          topRight: Radius.circular(20),
        ),
      ),
      child: Column(
        children: [
          InkWell(
            onTap: () => setState(() => _expanded = !_expanded),
            child: SizedBox(
              height: 46,
              child: Row(children: [
                const SizedBox(width: 16),
                _pulse(s.running),
                const SizedBox(width: 8),
                Text(s.running ? '运行中 · ${s.logs.length} 条' : '日志',
                  style: const TextStyle(fontWeight: FontWeight.w600)),
                const Spacer(),
                if (s.running)
                  TextButton.icon(
                    onPressed: s.stopScript,
                    icon: const Icon(Icons.stop_rounded, size: 18),
                    label: const Text('停止'),
                    style: TextButton.styleFrom(foregroundColor: Colors.redAccent),
                  ),
                IconButton(
                  icon: Icon(_expanded
                    ? Icons.keyboard_arrow_down_rounded
                    : Icons.keyboard_arrow_up_rounded),
                  onPressed: () => setState(() => _expanded = !_expanded),
                ),
              ]),
            ),
          ),
          if (_expanded)
            Expanded(
              child: Container(
                color: Colors.black87,
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                child: ListView.builder(
                  reverse: true,
                  itemCount: s.logs.length,
                  itemBuilder: (_, i) => Padding(
                    padding: const EdgeInsets.symmetric(vertical: 1),
                    child: Text(
                      s.logs[s.logs.length - 1 - i],
                      style: const TextStyle(color: Colors.greenAccent,
                        fontSize: 12, fontFamily: 'monospace'),
                    ),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _pulse(bool on) => AnimatedContainer(
    duration: const Duration(milliseconds: 500),
    width: 10, height: 10,
    decoration: BoxDecoration(
      color: on ? Colors.greenAccent : Colors.grey,
      shape: BoxShape.circle,
      boxShadow: on ? [BoxShadow(
        color: Colors.greenAccent.withOpacity(0.6),
        blurRadius: 8, spreadRadius: 2)] : null,
    ),
  );
}
