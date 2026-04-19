import 'dart:async';
import 'dart:io';
import 'dart:ui' as ui;
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../app_state.dart';
import '../services/native_service.dart';

/// OCR 交互式测试页
/// - 点"抓屏" → 拿一张当前屏幕的截图
/// - 点"全屏识别" → 识别整张截图
/// - 在图上拖拽 → 框选,再点"识别选区"只识别该区域(快得多)
/// - 打开"连续" → 每秒自动抓屏+识别,实时看 FPS/耗时
class OcrTestPage extends StatefulWidget {
  const OcrTestPage({super.key});
  @override
  State<OcrTestPage> createState() => _OcrTestPageState();
}

class _OcrTestPageState extends State<OcrTestPage> {
  final _imgKey = GlobalKey();
  final _native = NativeService.instance;

  String? _framePath;
  Size? _imgPx; // 原图像素尺寸
  Size? _shownSize; // 当前显示尺寸
  Offset? _shownOffset;

  Offset? _dragStartWidget;
  Rect? _selWidget; // widget 坐标下的选区
  OcrOutcome? _last;
  final List<int> _recentMs = [];

  bool _continuous = false;
  Timer? _timer;

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  Future<void> _grabFrame() async {
    final app = context.read<AppState>();
    if (!app.capture) {
      final ok = await _native.requestMediaProjection();
      if (!ok) return;
      await Future.delayed(const Duration(milliseconds: 600));
    }
    final p = await _native.captureFrame();
    if (p == null || !mounted) return;
    final sz = await _decodeSize(p);
    setState(() {
      _framePath = p;
      _imgPx = sz;
      _selWidget = null;
      _last = null;
    });
  }

  Future<void> _runFull() async {
    if (_framePath == null) { await _grabFrame(); if (_framePath == null) return; }
    final r = await _native.ocrRecognizeFile(_framePath!);
    if (r == null) return;
    setState(() {
      _last = r;
      _recentMs.add(r.elapsedMs);
      if (_recentMs.length > 10) _recentMs.removeAt(0);
    });
  }

  Future<void> _runSel() async {
    if (_framePath == null || _selWidget == null || _imgPx == null ||
        _shownSize == null || _shownOffset == null) return;
    // widget 坐标 → 原图像素坐标
    final roi = _widgetRectToImage(_selWidget!);
    if (roi == null) return;
    final r = await _native.ocrRecognizeFile(_framePath!,
      roi: [roi.left.toInt(), roi.top.toInt(),
            roi.right.toInt(), roi.bottom.toInt()]);
    if (r == null) return;
    setState(() {
      _last = r;
      _recentMs.add(r.elapsedMs);
      if (_recentMs.length > 10) _recentMs.removeAt(0);
    });
  }

  void _toggleContinuous() {
    setState(() => _continuous = !_continuous);
    _timer?.cancel();
    if (_continuous) {
      _timer = Timer.periodic(const Duration(milliseconds: 800), (_) async {
        if (!mounted) return;
        await _grabFrame();
        if (_selWidget != null && _imgPx != null) {
          await _runSel();
        } else {
          await _runFull();
        }
      });
    }
  }

  Rect? _widgetRectToImage(Rect r) {
    if (_imgPx == null || _shownSize == null || _shownOffset == null) return null;
    final sx = _imgPx!.width / _shownSize!.width;
    final sy = _imgPx!.height / _shownSize!.height;
    final local = r.translate(-_shownOffset!.dx, -_shownOffset!.dy);
    return Rect.fromLTRB(
      (local.left * sx).clamp(0, _imgPx!.width),
      (local.top * sy).clamp(0, _imgPx!.height),
      (local.right * sx).clamp(0, _imgPx!.width),
      (local.bottom * sy).clamp(0, _imgPx!.height),
    );
  }

  Future<Size> _decodeSize(String path) async {
    final bytes = await File(path).readAsBytes();
    final codec = await ui.instantiateImageCodec(bytes);
    final frame = await codec.getNextFrame();
    return Size(frame.image.width.toDouble(), frame.image.height.toDouble());
  }

  @override
  Widget build(BuildContext context) {
    final app = context.watch<AppState>();
    final cs = Theme.of(context).colorScheme;
    final avgMs = _recentMs.isEmpty ? null
        : (_recentMs.reduce((a, b) => a + b) / _recentMs.length).round();
    return Scaffold(
      appBar: AppBar(
        title: const Text('OCR 测试'),
        actions: [
          Padding(
            padding: const EdgeInsets.only(right: 12),
            child: FilterChip(
              selected: _continuous,
              onSelected: (_) => _toggleContinuous(),
              avatar: Icon(_continuous
                ? Icons.pause_circle_rounded
                : Icons.loop_rounded, size: 16),
              label: Text(_continuous ? '暂停' : '连续'),
            ),
          ),
        ],
      ),
      body: Column(children: [
        _StatusBanner(app: app, lastMs: _last?.elapsedMs,
          avgMs: avgMs, linesCount: _last?.lines.length ?? 0),
        Padding(
          padding: const EdgeInsets.fromLTRB(12, 10, 12, 0),
          child: Row(children: [
            Expanded(child: FilledButton.icon(
              onPressed: _grabFrame,
              icon: const Icon(Icons.photo_camera_rounded, size: 18),
              label: const Text('抓屏'),
            )),
            const SizedBox(width: 8),
            Expanded(child: FilledButton.tonalIcon(
              onPressed: app.ocr ? _runFull : null,
              icon: const Icon(Icons.text_fields_rounded, size: 18),
              label: const Text('全屏识别'),
            )),
            const SizedBox(width: 8),
            Expanded(child: FilledButton.tonalIcon(
              onPressed: (app.ocr && _selWidget != null) ? _runSel : null,
              icon: const Icon(Icons.crop_rounded, size: 18),
              label: const Text('识别选区'),
            )),
          ]),
        ),
        const SizedBox(height: 8),
        Expanded(
          child: Container(
            margin: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: cs.surfaceContainerHigh,
              borderRadius: BorderRadius.circular(16),
            ),
            clipBehavior: Clip.antiAlias,
            child: _framePath == null
              ? _EmptyHint(onGrab: _grabFrame)
              : _ImageCanvas(
                  framePath: _framePath!,
                  imgPx: _imgPx,
                  sel: _selWidget,
                  lines: _last?.lines ?? const [],
                  imgKey: _imgKey,
                  onSelStart: (off) {
                    _dragStartWidget = off;
                    setState(() => _selWidget = Rect.fromPoints(off, off));
                  },
                  onSelUpdate: (off) {
                    if (_dragStartWidget == null) return;
                    setState(() {
                      _selWidget = Rect.fromPoints(_dragStartWidget!, off);
                    });
                  },
                  onSelEnd: () {},
                  onLayout: (size, offset) {
                    _shownSize = size;
                    _shownOffset = offset;
                  },
                ),
          ),
        ),
        if (_last != null) _ResultsList(lines: _last!.lines,
          imgToWidget: _imgPointToWidget,
          onTapLine: (line) {
            if (!app.accessibility || !app.capture) return;
            // 真实屏幕坐标 = 原图坐标 (capture 是全屏)
            // 这里需要通过 NativeBridge 下发一次点击,但 OcrTestPage 里只读
            // 做法:让 ScriptRunnerService 立刻执行一个一次性 click 脚本
            final jsonStr = '{"name":"ocr_click","loop":1,"actions":[{"type":"click","x":${line.centerX},"y":${line.centerY}}]}';
            _native.runScript(jsonStr);
          },
        ),
      ]),
    );
  }

  Offset? _imgPointToWidget(int px, int py) {
    if (_imgPx == null || _shownSize == null || _shownOffset == null) return null;
    final sx = _shownSize!.width / _imgPx!.width;
    final sy = _shownSize!.height / _imgPx!.height;
    return Offset(px * sx + _shownOffset!.dx, py * sy + _shownOffset!.dy);
  }
}

class _StatusBanner extends StatelessWidget {
  final AppState app;
  final int? lastMs;
  final int? avgMs;
  final int linesCount;
  const _StatusBanner({required this.app, required this.lastMs,
      required this.avgMs, required this.linesCount});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    Widget chip(String text, {Color? color, IconData? icon}) => Container(
      margin: const EdgeInsets.only(right: 6),
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: (color ?? cs.primary).withValues(alpha: 0.1),
        borderRadius: BorderRadius.circular(10),
      ),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        if (icon != null) Icon(icon, size: 14, color: color ?? cs.primary),
        if (icon != null) const SizedBox(width: 4),
        Text(text, style: TextStyle(
          fontSize: 12, fontWeight: FontWeight.w600,
          color: color ?? cs.primary)),
      ]),
    );
    final ocrCol = app.ocr ? Colors.green.shade600
        : (app.ocrLoading ? Colors.amber.shade700 : Colors.grey);
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 2),
      child: Row(children: [
        chip(app.ocr ? 'OCR 就绪' : (app.ocrLoading ? '模型加载中' : '模型未加载'),
            color: ocrCol,
            icon: app.ocr ? Icons.check_circle_rounded
                          : Icons.downloading_rounded),
        if (lastMs != null) chip('${lastMs}ms', icon: Icons.timer_outlined),
        if (avgMs != null && avgMs != lastMs)
          chip('avg ${avgMs}ms', color: cs.secondary),
        chip('$linesCount 行', color: cs.tertiary,
          icon: Icons.text_snippet_outlined),
      ]),
    );
  }
}

class _EmptyHint extends StatelessWidget {
  final VoidCallback onGrab;
  const _EmptyHint({required this.onGrab});
  @override
  Widget build(BuildContext context) {
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(Icons.image_rounded, size: 60,
            color: Theme.of(context).colorScheme.outline),
          const SizedBox(height: 12),
          const Text('点"抓屏"开始测试',
            style: TextStyle(fontSize: 14, color: Colors.grey)),
          const SizedBox(height: 16),
          FilledButton.tonalIcon(
            onPressed: onGrab,
            icon: const Icon(Icons.photo_camera_rounded),
            label: const Text('抓取当前屏幕'),
          ),
        ],
      ),
    );
  }
}

class _ImageCanvas extends StatefulWidget {
  final String framePath;
  final Size? imgPx;
  final Rect? sel;
  final List<OcrLine> lines;
  final GlobalKey imgKey;
  final void Function(Offset) onSelStart;
  final void Function(Offset) onSelUpdate;
  final VoidCallback onSelEnd;
  final void Function(Size, Offset) onLayout;
  const _ImageCanvas({
    required this.framePath, required this.imgPx, required this.sel,
    required this.lines, required this.imgKey,
    required this.onSelStart, required this.onSelUpdate, required this.onSelEnd,
    required this.onLayout,
  });
  @override
  State<_ImageCanvas> createState() => _ImageCanvasState();
}

class _ImageCanvasState extends State<_ImageCanvas> {
  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(builder: (ctx, c) {
      final maxW = c.maxWidth;
      final maxH = c.maxHeight;
      double sw = maxW, sh = maxH;
      double ox = 0, oy = 0;
      if (widget.imgPx != null) {
        final ar = widget.imgPx!.width / widget.imgPx!.height;
        if (maxW / maxH > ar) {
          sh = maxH; sw = sh * ar; ox = (maxW - sw) / 2;
        } else {
          sw = maxW; sh = sw / ar; oy = (maxH - sh) / 2;
        }
      }
      WidgetsBinding.instance.addPostFrameCallback((_) {
        widget.onLayout(Size(sw, sh), Offset(ox, oy));
      });
      return GestureDetector(
        onPanStart: (d) => widget.onSelStart(d.localPosition),
        onPanUpdate: (d) => widget.onSelUpdate(d.localPosition),
        onPanEnd: (_) => widget.onSelEnd(),
        child: Stack(children: [
          Positioned(
            left: ox, top: oy, width: sw, height: sh,
            child: Image.file(File(widget.framePath),
              key: widget.imgKey, fit: BoxFit.fill),
          ),
          Positioned.fill(
            child: CustomPaint(
              painter: _OverlayPainter(
                sel: widget.sel,
                lines: widget.lines,
                imgPx: widget.imgPx,
                shown: Size(sw, sh),
                offset: Offset(ox, oy),
              ),
            ),
          ),
        ]),
      );
    });
  }
}

class _OverlayPainter extends CustomPainter {
  final Rect? sel;
  final List<OcrLine> lines;
  final Size? imgPx;
  final Size shown;
  final Offset offset;
  _OverlayPainter({required this.sel, required this.lines,
      required this.imgPx, required this.shown, required this.offset});

  @override
  void paint(Canvas canvas, Size size) {
    // 识别结果框
    if (imgPx != null) {
      final sx = shown.width / imgPx!.width;
      final sy = shown.height / imgPx!.height;
      final paint = Paint()
        ..color = Colors.greenAccent
        ..strokeWidth = 2
        ..style = PaintingStyle.stroke;
      final fill = Paint()
        ..color = Colors.greenAccent.withValues(alpha: 0.12);
      for (final l in lines) {
        final path = Path();
        for (int i = 0; i < 4; i++) {
          final x = l.box[i * 2] * sx + offset.dx;
          final y = l.box[i * 2 + 1] * sy + offset.dy;
          if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.close();
        canvas.drawPath(path, fill);
        canvas.drawPath(path, paint);
        // 文本小标签
        final tp = TextPainter(
          text: TextSpan(
            text: l.text,
            style: const TextStyle(color: Colors.white, fontSize: 11,
              fontWeight: FontWeight.w600,
              backgroundColor: Color(0xCC000000)),
          ),
          textDirection: TextDirection.ltr,
        )..layout(maxWidth: 260);
        final tx = l.box[0] * sx + offset.dx;
        final ty = l.box[1] * sy + offset.dy - tp.height;
        tp.paint(canvas, Offset(tx, ty.clamp(0, size.height)));
      }
    }
    // 选区
    if (sel != null) {
      final p = Paint()
        ..color = Colors.redAccent
        ..strokeWidth = 2
        ..style = PaintingStyle.stroke;
      canvas.drawRect(sel!, p);
      canvas.drawRect(sel!, Paint()
        ..color = Colors.redAccent.withValues(alpha: 0.12));
    }
  }

  @override
  bool shouldRepaint(_OverlayPainter old) =>
    old.sel != sel || old.lines != lines || old.shown != shown;
}

class _ResultsList extends StatelessWidget {
  final List<OcrLine> lines;
  final Offset? Function(int px, int py) imgToWidget;
  final void Function(OcrLine) onTapLine;
  const _ResultsList({required this.lines, required this.imgToWidget,
      required this.onTapLine});

  @override
  Widget build(BuildContext context) {
    if (lines.isEmpty) {
      return Container(
        height: 44,
        padding: const EdgeInsets.symmetric(horizontal: 16),
        alignment: Alignment.centerLeft,
        child: const Text('暂无识别结果',
          style: TextStyle(color: Colors.grey, fontSize: 13)),
      );
    }
    final cs = Theme.of(context).colorScheme;
    return Container(
      height: 180,
      margin: const EdgeInsets.fromLTRB(12, 0, 12, 12),
      padding: const EdgeInsets.symmetric(vertical: 6),
      decoration: BoxDecoration(
        color: cs.surfaceContainerHigh,
        borderRadius: BorderRadius.circular(16),
      ),
      child: ListView.separated(
        padding: const EdgeInsets.symmetric(horizontal: 10),
        itemCount: lines.length,
        separatorBuilder: (_, __) => const Divider(height: 1),
        itemBuilder: (_, i) {
          final l = lines[i];
          return ListTile(
            dense: true,
            title: Text(l.text, style: const TextStyle(fontSize: 14)),
            subtitle: Text('置信度 ${(l.score * 100).toStringAsFixed(0)}%  ·  '
                           '中心 (${l.centerX}, ${l.centerY})',
              style: const TextStyle(fontSize: 11)),
            leading: CircleAvatar(
              radius: 12,
              backgroundColor: cs.primaryContainer,
              child: Text('${i + 1}', style: const TextStyle(fontSize: 11)),
            ),
            trailing: IconButton(
              icon: const Icon(Icons.touch_app_rounded, size: 20),
              tooltip: '模拟点击',
              onPressed: () => onTapLine(l),
            ),
          );
        },
      ),
    );
  }
}
