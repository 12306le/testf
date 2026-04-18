# 自动点击器 (Flutter + Android)

基于 Flutter + Kotlin 原生的安卓自动点击器，支持**悬浮窗选点 / 找色 / 找图 / 脚本循环**，免 root。

## 功能清单

- ✅ 悬浮控制面板（可拖动）
- ✅ 透明全屏层拾取坐标
- ✅ MediaProjection 截屏 + 单点取色
- ✅ 模板匹配找图（纯 Kotlin，无 OpenCV 依赖）
- ✅ 脚本编辑器：点击 / 长按 / 滑动 / 等待 / 判色 / 找色 / 找图 / 循环
- ✅ 找到后自动点击、分支执行（onFound / onNotFound）
- ✅ SharedPreferences 持久化
- ✅ 前台服务 + 通知栏常驻
- ✅ GitHub Actions 自动构建

## 运行需要的权限

| 权限 | 用途 | 授权方式 |
|------|------|---------|
| SYSTEM_ALERT_WINDOW | 悬浮窗 | 首页点"去授权" → 系统设置开启 |
| BIND_ACCESSIBILITY_SERVICE | 执行点击 | 设置 → 无障碍 → 自动点击器-无障碍 |
| MediaProjection | 截屏找色找图 | 首页点"去授权" → 运行时弹窗同意 |
| FOREGROUND_SERVICE + 通知 | 保活 | 安装后默认允许即可 |

## 目录结构

```
auto_clicker/
├── android/app/src/main/
│   ├── AndroidManifest.xml
│   ├── kotlin/com/autoclicker/app/
│   │   ├── MainActivity.kt                    # Flutter 宿主 + MethodChannel
│   │   ├── NativeBridge.kt                    # 事件 / 方法总线
│   │   ├── AutoClickAccessibilityService.kt   # 点击/手势执行
│   │   ├── FloatingWindowService.kt           # 悬浮控制面板
│   │   ├── PointPickerOverlay.kt              # 全屏拾取层
│   │   ├── ScreenCaptureService.kt            # MediaProjection 截屏
│   │   ├── ImageMatcher.kt                    # 找色/找图算法
│   │   ├── ScriptRunner.kt                    # 脚本执行引擎
│   │   └── ScriptRunnerService.kt             # 执行前台服务
│   └── res/xml/accessibility_config.xml
├── lib/
│   ├── main.dart
│   ├── app_state.dart
│   ├── models/script.dart
│   ├── services/{native_service,storage_service}.dart
│   └── pages/{home_page,script_editor_page}.dart
├── pubspec.yaml
└── .github/workflows/build.yml
```

## 本地编译

```bash
# 一次性准备
flutter pub get

# Debug APK（最快，5-10 分钟首次）
flutter build apk --debug --target-platform android-arm64

# Release APK
flutter build apk --release --target-platform android-arm64

# 直接 run 到连接的设备
flutter run
```

APK 输出在 `build/app/outputs/flutter-apk/`。

## GitHub Actions 编译

- 推到 main / master：自动跑 debug 构建，产物在 Artifacts
- 打 `v*` tag：自动发布 Release
- 手动触发：Actions → Build Android APK → Run workflow，可选 release

预计耗时：首次无缓存 ~15 分钟，有缓存 ~6 分钟。

## 脚本 JSON 示例

```json
{
  "name": "签到",
  "loop": 1,
  "actions": [
    { "type": "sleep", "ms": 500 },
    { "type": "findImage", "path": "/data/user/0/com.autoclicker.app/files/templates/tpl_xxx.png",
      "threshold": 0.9, "clickOnFound": true,
      "onNotFound": [ { "type": "sleep", "ms": 1000 } ] },
    { "type": "sleep", "ms": 300 },
    { "type": "findColor", "argb": -16711936, "tolerance": 8,
      "roi": [0, 0, 1080, 500], "clickOnFound": true },
    { "type": "sleepRandom", "min": 200, "max": 600 }
  ]
}
```

## 图色匹配性能说明

- 默认 1080p 全屏模板匹配 + 4x 金字塔 + 2 像素步长：单次 < 100 ms
- 设 ROI 小于半屏可再快 3-5 倍
- 只判单点颜色（`checkColor`）≈ 2 ms
- 若准确度不够，把 `scale` 降到 2 或 1（代码常量，位于 ImageMatcher.findTemplate）

## 已知限制

- 嵌套 `loop` 的子动作编辑 UI 需要手动写 JSON（或另建脚本再导入）。v2 再补可视化。
- MediaProjection 每次应用被杀都要重新授权，这是 Android 的硬限制。
- 部分国产 ROM 要手动给应用"显示在其它应用上层""自启动""后台运行"三个权限，否则服务会被杀。
- Google Play 上架无障碍服务有高门槛。此仓库仅供学习与自用。

## 免责声明

仅限个人设备自动化、辅助功能开发、自动化测试用途。不要用于破坏他人应用体验、游戏外挂、刷量等违反平台协议的场景。
