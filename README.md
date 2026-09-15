# 小爱音量跟随 1.1.0

LSPosed 模块：保留小爱的独立音频流，让其音量档位跟随媒体音量，并约束小爱自身的音量自动调整。小爱激活时，音量加减键直接调整媒体，再由小爱跟随。

## 使用

1. 安装 `XiaoAiVolumeSync-1.1.0.apk`。
2. 在 LSPosed 中启用「小爱音量跟随」。勾选 `com.miui.voiceassist`（超级小爱/小爱同学）和 `android`（系统框架）。
3. 重启设备。新增的音量键路由 Hook 需要在系统进程启动时加载。
4. 打开本模块，点「刷新运行状态」。看到“小爱进程实时回报”才表明目标进程已加载模块；页面会区分历史回报与本次实际回应。

已经在本次连接的平板完成安装、启用和实机验证，详细结果见同目录的验证报告。

停用：在 LSPosed 禁用本模块，再重启设备。卸载 APK 后也需要重启，已注入的代码不会因删除 APK 立即从运行进程消失。停用不会恢复以前的小爱音量值；它会保留最后一次的跟随值，然后恢复独立调节。

## 规则与边界

- 媒体流 3 → 助手流 11，单向同步。
- 媒体为 0，助手为 0；媒体非零，按最大档位比例四舍五入，至少保留助手 1 档。当前设备 80/150 → 8/15。
- 读取的是媒体保存的档位，而非临时静音后的有效音量。小爱识别时临时静音媒体不会使助手归零。
- 同步发生在应用启动、媒体音量变化、助手音量变化、音频设备变更、助手 AudioTrack 创建与播放前。
- 小爱的 setStreamVolume(11, …) 写入会换算为媒体目标值；单独增减助手档位也受约束。针对实测版本，还处理了 `ensureXiaoaiVolume()` 的 30% 最低音量逻辑。
- 小爱主进程未运行时，不保证音量面板里的助手滑块实时同步；重新启动和播放前会校正。Android 对后台广播的调度可能带来延迟。
- 系统中的助手滑块仍保留；手动拖动助手滑块仍会跟随媒体值。音量键加减则由系统 Hook 直接转发到媒体流，不会出现先改助手又被拉回的冲突。
- 不修改音频流用途和音频焦点，不将助手调整回写媒体。没有网络权限、后台轮询服务或开机接收器。
- 单独的用户静音按钮、免打扰、蓝牙/耳机以及实际语音主观听感，仍需要对应场景验证；音量索引同步不等于两种声音的响度相同。

## 已验证环境

- 小米平板 M2105K81AC / elish
- HyperOS OS3.0.6.0.WNXCNXM，Android 16 / API 36
- 小爱 7.13.33.0017，versionCode 507013033
- LSPosed IT v1.9.2-it (7478)

APK 最低 Android 12 / API 31，但没有据此宣称适配所有 Android 12+ 设备。它要求独立助手流、最低档位为 0、小爱拥有相关音频权限；不满足时初始化失败并保留原系统行为。新小爱版本仍使用通用 Android Hook，混淆方法的补充 Hook 仅针对上面已确认的版本。

## 技术实现

- `HookEntry.java`：作用域校验、应用初始化、音量写入和 AudioTrack Hook。
- `SystemKeyRouter.java` / `KeyRoutePolicy.java`：系统 AudioService 中只将 `stream=11`、方向为加减、且带 `FLAG_FROM_KEY` 的调用改为 `stream=3`；系统原有音量步进、权限检查、设备选择和应用流程继续执行。其他流、非按键调用和静音方向不重定向。系统部分不会轮询或回写助手音量。
- `SyncController.java`：读取隐藏音量接口、监听变化、同步、路由变更后补偿、重入保护及状态回报。
- `VolumeMath.java`：独立的档位换算逻辑。
- `StatusProvider.java`：音量状态只接受小爱实际 UID 的回报；系统按键路由状态只接受 SYSTEM_UID 回报。状态刷新广播受模块签名权限保护。
- `MainActivity.java`：显示真实进程回报及使用说明。
- `DebugProbe.java`：仅诊断构建注册，且要求发送方具有 DUMP 权限；正式版不注册此入口。

Android 16 的公开 `getStreamMinVolume(11)` 会拒绝隐藏助手流，所以使用 `getStreamMinVolumeInt(11)`。读取别名需要小爱没有的 MODIFY_AUDIO_SETTINGS_PRIVILEGED 权限，本设备改用系统 `config_useAssistantVolume` / `config_singleVolume` 资源判断；没有额外授予小爱权限。最终独立性还通过设备 dumpsys 与实机测试核实。

日志标签：`XiaoAiVolumeSync`。只记录模块行为和音量数字，不记录语音内容。

## 从源码构建（Windows PowerShell）

不需要 Android Studio 或 Gradle；构建使用官方 Android SDK 的 AAPT2、D8、zipalign、apksigner 和 JDK。使用纯 Java，Xposed API 仅用于编译，不打包到 APK 中。

准备：

- JDK（本次使用现有 Temurin 25.0.3，源码目标 Java 8）。
- Android SDK platform 36 / revision 2：`https://dl.google.com/android/repository/platform-36_r02.zip`
  - SHA-1：`2c1a80dd4d9f7d0e6dd336ec603d9b5c55a6f576`
- Android Build Tools 36.0.0（Windows）：`https://dl.google.com/android/repository/build-tools_r36_windows.zip`
  - SHA-1：`f16ccffd34de8790dede813a6c7d8e2c11a27b50`
- Xposed API 82：`https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar`
  - SHA-256：`f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25`

SDK 布局：`platforms/android-36/android.jar`，以及 `build-tools/<目录>/aapt2.exe` 等工具。原始 Build Tools ZIP 的目录名是 `android-16`，构建脚本可以识别。

```powershell
./build.ps1 -JavaHome 'C:/path/to/jdk' `
  -SdkPath 'C:/path/to/android-sdk' `
  -XposedApiJar 'C:/path/to/api-82.jar' `
  -WorkDir 'C:/path/to/build-release' `
  -OutputApk 'C:/path/to/XiaoAiVolumeSync-1.1.0.apk'
```

构建会运行换算测试、生成 APK，并验证签名与 ZIP 对齐。`-Diagnostics` 用于诊断构建；请为诊断版和正式版使用不同 WorkDir。

首次构建会在 WorkDir 的父目录生成本地签名文件 `xiaoaivolumesync-signing.p12`（本地构建固定密码 `local-build-key`）。这是为了复用本机签名，不是密码保护方案；签名文件不在源码包内，应自行妥善保存。丢失/更换签名后无法直接覆盖更新原安装包，需要卸载后安装新签名版本。

## 验证方法

单元测试覆盖零值、端点、单调性、非零最小档、上下限、不同最大音量和溢出边界，也覆盖按键路由和其他流/非按键/静音操作的隔离。诊断构建在真正的小爱进程内验证：

1. 尝试把助手设为最大值时是否被目标值约束。
2. 助手增大一档是否仍保持媒体目标值。
3. 临时静音媒体期间是否保留助手目标，且媒体保存档位不变。
4. 调用真实 `ensureXiaoaiVolume()` 后是否仍保持低档位。
5. 使用 USAGE_ASSISTANT 创建并启动静音 PCM AudioTrack，检查保留助手用途和正确音量。

没有以“返回成功”代替实际读回：外部测试会逐次读取媒体与助手音量，并在结束后恢复原媒体值。普通 adb shell 的媒体写入在该 HyperOS 上会被 AppOps 拒绝，实机测试使用了用户已授权的 Root 命令。

## 上游资料

- [Android AudioManager 源码](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android16-release/media/java/android/media/AudioManager.java)
- [Android AudioService 源码](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android16-release/services/core/java/com/android/server/audio/AudioService.java)
- [Xposed API](https://api.xposed.info/)
- [LSPosed 文档](https://github.com/LSPosed/LSPosed/wiki)
- [AAPT2](https://developer.android.com/tools/aapt2)
- [apksigner](https://developer.android.com/tools/apksigner)
