# AutoDY

AutoDY 是一个用于隔空刷短视频的 Android 辅助工具。开启无障碍服务后，App 可以在后台识别用户动作，并自动触发屏幕中间向上滑动手势。

## 功能

- 张嘴识别模式：通过前置摄像头检测嘴巴张开，识别到后触发上滑。
- 声音识别模式：通过麦克风监听响指类短促高峰声音，识别到后触发上滑。
- 模式切换：张嘴识别和声音识别二选一运行，切换后会关闭另一种检测资源。
- 锁屏低功耗：锁屏或熄屏后自动关闭相机/麦克风检测，并停止本次识别；下次需要手动点击开始。
- 张嘴检测间隔可调：支持在 `1000ms` 到 `5000ms` 之间调节检测频率。
- 运行日志：主界面可查看、复制和清空日志，方便调试识别阈值和权限问题。
- 测试上滑：可在主界面手动测试无障碍上滑手势是否正常。

## 权限

AutoDY 需要以下权限才能工作：

- 相机权限：张嘴识别模式使用前置摄像头。
- 麦克风权限：声音识别模式监听响指。
- 通知权限：前台服务常驻通知。
- 无障碍服务：执行上滑手势。

## 安装

项目根目录提供调试签名 APK：

```text
AutoDY.apk
```

安装后请在 App 内按提示授权相机/麦克风/通知权限，并在系统设置中开启 AutoDY 的无障碍服务。

## 使用

1. 打开 AutoDY。
2. 选择“张嘴识别”或“声音识别”。
3. 如果选择张嘴识别，可调整嘴巴检测间隔。
4. 点击“开始识别”。
5. 打开需要刷的视频应用。
6. 张嘴或打响指触发上滑。

锁屏或熄屏时，AutoDY 会关闭相机/麦克风检测资源并停止本次识别会话；解锁后不会自动恢复，需要回到 App 手动点击“开始识别”。

## 开发

当前工程使用 Android Gradle Plugin 构建，目标 SDK 为 Android 16 / API 36。核心逻辑位于：

- `app/src/main/java/com/autody/blinkscroll/MainActivity.java`
- `app/src/main/java/com/autody/blinkscroll/BlinkDetectionService.java`
- `app/src/main/java/com/autody/blinkscroll/BlinkAccessibilityService.java`
- `app/src/main/java/com/autody/blinkscroll/BlinkSettings.java`

本项目主要用于个人辅助操作和实验性调试，请遵守目标 App 和平台的使用规则。
