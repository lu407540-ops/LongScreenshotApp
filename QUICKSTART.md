# 快速开始指南

## 项目已创建完成！

所有文件已保存在：`c:\Users\Administrator\WorkBuddy\20260501080244\LongScreenshotApp\`

### 📂 已创建的文件清单

```
LongScreenshotApp/
├── build.gradle              ✅ 项目构建配置
├── settings.gradle           ✅ 项目设置
├── app/
│   ├── build.gradle         ✅ 模块构建配置
│   └── src/main/
│       ├── AndroidManifest.xml           ✅ 权限配置
│       ├── java/com/longscreenshot/
│       │   ├── MainActivity.java        ✅ 主界面
│       │   ├── ScreenshotService.java   ✅ 截图核心服务
│       │   └── FloatingWindowService.java ✅ 悬浮窗服务
│       └── res/
│           ├── layout/
│           │   ├── activity_main.xml    ✅ 主界面布局
│           │   └── floating_window.xml ✅ 悬浮窗布局
│           └── drawable/
│               ├── card_bg.xml          ✅ 卡片背景
│               └── floating_bg.xml      ✅ 悬浮窗背景
└── USAGE.md                 ✅ 详细使用说明
```

---

## 🚀 如何编译和安装

### 方法一：用 Android Studio（最简单）

1. 下载安装 [Android Studio](https://developer.android.com/studio)
2. 打开 Android Studio → 点击 **Open**
3. 选择 `c:\Users\Administrator\WorkBuddy\20260501080244\LongScreenshotApp`
4. 等待 Gradle 自动同步（首次可能需要几分钟）
5. 用 USB 连接你的安卓手机，开启 **USB 调试**
6. 点击 ▶️ **Run** 按钮 → 选择你的手机 → 自动安装

### 方法二：命令行编译（需要配置 Android SDK）

```bash
cd c:\Users\Administrator\WorkBuddy\20260501080244\LongScreenshotApp

# Windows
gradlew.bat assembleDebug

# 安装到手机（需要 adb）
adb install app\build\outputs\apk\debug\app-debug.apk
```

---

## 📱 使用步骤

### 第一步：授权权限

打开 App 后，系统会依次请求：

1. **存储权限** → 点「允许」（保存截图）
2. **悬浮窗权限** → 跳转设置，找到「长截图工具」→ 开启悬浮窗
3. **屏幕录制权限** → 点「立即开始」

### 第二步：开始截图

1. 在 App 主界面点 **「开始长截图」**
2. 系统弹出录屏确认框 → 点 **「立即开始」**
3. **切换到要截图的 App**（比如微信聊天记录、网页等）
4. **慢慢滚动屏幕**，观察悬浮窗：
   - 实时显示 **宽度（px）**
   - 实时显示 **高度（px）** ← 这个就是你想要的
   - 实时显示 **文件大小（KB）**
5. 截图完成后，回到「长截图工具」App
6. 点 **「停止并保存」**

### 第三步：查看截图

截图自动保存到：`/sdcard/DCIM/LongScreenshot/long_screenshot_xxx.png`

打开手机相册就能看到！

---

## ⚠️ 注意事项

- **Android 5.0+** 才能用（需要 MediaProjection API）
- 截图过程不要关闭 App 后台
- 悬浮窗可以**拖动**，别挡住要截的内容
- 滚动速度不要太快，给截图留点时间（每次截图间隔 800ms）

---

## 🐛 常见问题

| 问题 | 解决方法 |
|------|---------|
| 悬浮窗不显示 | 去手机设置 → 应用管理 → 长截图工具 → 悬浮窗权限，开启 |
| 截图保存失败 | 检查存储权限，Android 10+ 需要「所有文件访问权限」 |
| 截图模糊 | 正常，截图分辨率 = 屏幕分辨率 |
| 无法安装 | 手机需要开启「允许安装未知来源应用」 |

---

## 💡 技术说明

- 使用 **MediaProjection API** 捕获屏幕（Android 5.0+ 原生支持）
- 使用 **ImageReader** 读取屏幕像素
- 使用 **前台服务** 保证截图不被系统杀掉
- 使用 **悬浮窗** 实时显示高度和文件大小
- 图片自动拼接成一张完整长图

---

✅ **项目已完成！**
```