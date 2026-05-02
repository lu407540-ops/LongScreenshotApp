# 长截图工具 - 使用说明

## 功能介绍

一款 Android 长截图工具，支持：
- 截取其他 App 的屏幕内容
- 滚动过程中**实时显示**已截取高度（px）
- 实时显示图片宽度和文件大小估算
- 自动拼接成长图并保存到相册

## 项目结构

```
LongScreenshotApp/
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml       # 权限配置
│       ├── java/com/longscreenshot/
│       │   ├── MainActivity.java    # 主界面
│       │   ├── ScreenshotService.java  # 截图核心服务
│       │   └── FloatingWindowService.java  # 悬浮窗
│       └── res/
│           ├── layout/
│           │   ├── activity_main.xml   # 主界面布局
│           │   └── floating_window.xml # 悬浮窗布局
│           └── drawable/
│               ├── card_bg.xml      # 卡片背景
│               └── floating_bg.xml # 悬浮窗背景
├── build.gradle          # 项目构建配置
├── app/build.gradle      # 模块构建配置
└── settings.gradle       # 项目设置
```

## 安装步骤

### 方法一：Android Studio 编译（推荐）

1. 下载并安装 [Android Studio](https://developer.android.com/studio)
2. 打开 Android Studio → `Open an Existing Project`
3. 选择 `LongScreenshotApp` 文件夹
4. 等待 Gradle 同步完成
5. 连接 Android 手机（开启 USB 调试）
6. 点击 ▶️ `Run` 按钮，选择设备
7. 自动安装到手机

### 方法二：命令行编译

```bash
# 进入项目目录
cd LongScreenshotApp

# Windows
gradlew.bat assembleDebug

# Mac/Linux
./gradlew assembleDebug

# 安装到手机
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 使用流程

### 第一次使用（授权）

1. 打开 App，会提示授权：
   - **存储权限**：允许（保存截图用）
   - **悬浮窗权限**：允许（显示实时信息用）
   - **屏幕录制权限**：允许（截图用）

### 日常使用

```
步骤1：打开「长截图工具」App
      ↓
步骤2：点击「开始长截图」
      ↓
步骤3：在弹出的系统对话框点「立即开始」
      ↓
步骤4：切换到要截图的 App（如微信、浏览器）
      ↓
步骤5：滚动屏幕，观察悬浮窗实时数据
      ↓
步骤6：截图完成后，回到「长截图工具」点「停止并保存」
      ↓
步骤7：截图自动保存到 相册/DCIM/LongScreenshot/
```

## 悬浮窗显示内容

| 项目 | 说明 |
|------|------|
| 宽度 | 屏幕宽度（px），固定值 |
| 高度 | 当前已截取的总高度（px），**实时更新** |
| 大小 | 估算的 PNG 文件大小（KB） |

## 注意事项

- **Android 5.0+** 才能使用（需要 MediaProjection API）
- 截图过程中**不要关闭**「长截图工具」后台
- 悬浮窗可以**拖动**，避免遮挡内容
- 每次截图会自动拼接，最终保存为一张完整长图
- 保存路径：`/sdcard/DCIM/LongScreenshot/long_screenshot_时间戳.png`

## 常见问题

**Q：悬浮窗不显示？**
A：检查是否授予了「悬浮窗权限」，在系统设置 → 应用管理 → 长截图工具 → 悬浮窗，开启。

**Q：截图保存失败？**
A：检查存储权限是否授予，Android 10+ 需要「所有文件访问权限」。

**Q：截图模糊？**
A：这是系统 MediaProjection 的限制，截图分辨率等于屏幕分辨率。

**Q：能不能自动滚动截图？**
A：目前需要手动滚动，自动滚动功能需要无障碍服务权限，后续版本可以加上。

## 技术说明

- 使用 **MediaProjection API** 捕获屏幕
- 使用 **ImageReader** 读取截图像素数据
- 使用 **前台服务** 保证截图过程不被系统杀掉
- 使用 **悬浮窗（TYPE_APPLICATION_OVERLAY）** 显示实时信息
- 图片拼接使用 **Bitmap Canvas** 逐张绘制

## 后续优化方向

- [ ] 自动滚动截图（无障碍服务）
- [ ] 截图区域选择（只截取部分内容）
- [ ] 图片格式选择（PNG/JPG/WebP）
- [ ] 图片质量调节
- [ ] 直接分享截图
