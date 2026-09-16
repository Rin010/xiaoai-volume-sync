# 小爱音量设置 1.3.0

给已检查的 HyperOS 小爱同学提供三个独立开关。默认值保留 1.1.0 的行为：同步开、媒体播放关、前台按键转发开。

| 开关 | 打开后 |
| --- | --- |
| 小爱音量同步媒体音量 | 保留助手流 11；按最大档位比例从媒体流 3 单向同步，约束小爱自动改变助手档位。 |
| 小爱同学使用媒体音量 | 在小爱进程内把助手流 11 的音量读取重定向到媒体流 3，并把助手用途的 AudioTrack / MediaPlayer 改为媒体用途。小爱原先静音媒体和自动抬高音量的已识别内部操作会被拦截。 |
| 小爱同学前台按键修改媒体音量 | 小爱 Activity 在前台时选择媒体音量键；系统对误选到助手流 11 的按键加减请求也改为媒体流 3。 |

“同步”和“使用媒体音量”同时打开时，助手流档位仍会跟随，但小爱的播报以媒体流档位为准。关闭前台按键而仍使用助手流并开启同步时，助手音量键可能因同步而被拉回。这是用户自由组合开关的结果，建议该组合保持前台按键开。

“使用媒体音量”打开后，普通 Android 音量键也可能自然选到媒体流；此时第三个开关只决定是否额外转发误选到助手流的按键，无法强制媒体播放器的音量键改为助手流。

## 安装和操作

1. 安装本目录对应的 `XiaoAiVolumeSync-1.3.0.apk`。
2. 在 LSPosed 启用模块，作用域选 `com.miui.voiceassist` 和 `android`。
3. 重启平板，并在重启后解锁一次。系统框架的按键 Hook 需要重新加载。三个开关之后可在模块页面更改。
4. 切换“使用媒体音量”后重启小爱进程。小爱已创建的播放器和缓存的流类型不会因切换立即改变；模块页面可以检查实际选择的流和最近一次 AudioTrack 的流类型。

停用模块时，在 LSPosed 禁用后重启平板。媒体与助手已有档位保留最后一次的系统值。

## 适配范围

实测设备包括小米平板 M2105K81AC / elish（HyperOS OS3.0.6.0.WNXCNXM，小爱 7.13.33.0017 / 507013033）和小米 23116PN5BC / shennong（HyperOS OS3.0.306.0.WNBCNXM，小爱 7.13.21.0017 / 507013021），均为 Android 16 / API 36。最低 Android 12 / API 31 并不代表已在其他设备验证。

1.3.0 不再 Hook `k00.o`、`d00.o` 这类随小爱版本变化的混淆流选择类。核心路径固定在 Android 的 `AudioManager`、`AudioTrack` 和 `MediaPlayer` API，因此常规的小爱升级不再需要新增流选择类映射。小爱自己的媒体静音和自动改音量函数仍是私有实现；模块会按完整方法签名逐项探测，找不到时跳过该项，核心重定向仍可启用。升级后应在模块状态页确认最近 AudioTrack 为流 3 / 用途 1，并实际听一次播报。

同步使用媒体保存的音量档位，所以小爱短暂静音媒体不改变同步目标。设备的媒体范围 0–150、助手范围 0–15，例如媒体 80 → 助手 8。媒体为 0，助手为 0；媒体非零至少对应助手 1 档。关闭同步后，模块不会写入助手流档位。

系统仍可能显示独立小爱音量滑块。打开“使用媒体音量”并不改变整个 Android 音频框架的音量组，只改变小爱进程的读取与播放路径；其他助手应用不受这项切换影响。音频焦点请求仍保留。蓝牙/耳机、免打扰和完整自然对话的听感需要分别验收。

## 实现

- `RuntimeOptions.java` 通过只允许小爱 UID 与系统 UID 查询的状态 Provider 获取开关。页面保存修改后向两个作用域广播变更，不需要后台轮询。
- `SyncController.java` 和 `HookEntry.java` 保留 1.1.0 的音量换算、事件监听、启动和播放前校正；同步开关关闭时停止写入。
- `DirectMediaHooks.java` 在小爱进程内重定向助手流的 Android 框架音量读取，并处理 AudioTrack / MediaPlayer 的显式助手播放属性；小米私有的静音与自动音量函数按签名作为附加保护加载。
- `RuntimeOptions.java` 提供线程内旁路，使模块同步逻辑仍能读取独立助手流的真实档位，不会读到自己重定向后的媒体值。
- `SystemKeyRouter.java` 只在开关开启且小爱前台时转发带 `FLAG_FROM_KEY` 的助手流加减请求。它识别小爱 Activity 和 `voice_assist_root` 浮窗。前台状态由小爱 UID 报给模块 Provider，再由模块签名广播传给系统进程；按键时还核实报告进程的 PID/UID，避免小爱被强制停止后留下旧前台状态。
- 系统启动早于用户存储解锁时先采用上次内存值/默认值，收到用户解锁广播或页面状态请求后重新读取已保存的开关。
- `MainActivity.java` 提供三个开关和运行状态；`StatusProvider.java` 校验状态回报 UID，避免其他应用伪造小爱或系统状态。
- `DebugProbe.java`、`DirectMediaProbe.java` 只在诊断构建注册，并要求发送方具有 `DUMP` 权限。正式版不注册诊断广播。

日志标签 `XiaoAiVolumeSync`，只记录流与数字，不记录语音内容。没有网络权限。

## 从源码构建（Windows PowerShell）

使用 JDK、官方 Android SDK platform 36、Build Tools 36 和编译专用的 Xposed API 82，无需 Android Studio 或 Gradle。

- Android platform 36 revision 2：`https://dl.google.com/android/repository/platform-36_r02.zip`，SHA-1 `2c1a80dd4d9f7d0e6dd336ec603d9b5c55a6f576`
- Android Build Tools 36 Windows：`https://dl.google.com/android/repository/build-tools_r36_windows.zip`，SHA-1 `f16ccffd34de8790dede813a6c7d8e2c11a27b50`
- Xposed API 82：`https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar`，SHA-256 `f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25`

```powershell
./build.ps1 -JavaHome 'C:/path/to/jdk' `
  -SdkPath 'C:/path/to/android-sdk' `
  -XposedApiJar 'C:/path/to/api-82.jar' `
  -WorkDir 'C:/path/to/build-release' `
  -OutputApk 'C:/path/to/XiaoAiVolumeSync-1.3.0.apk'
```

构建会运行独立的音量换算和按键规则测试，构建 APK，验证 v3 签名与 ZIP 对齐。`-Diagnostics` 构建诊断版，应与正式版使用不同 WorkDir。签名文件保存在 WorkDir 的父目录 `xiaoaivolumesync-signing.p12`；它不进入源码包。首次构建生成的本地测试签名使用固定密码 `local-build-key`，这是签名复用方式，不是密码保护方案。

## 资料

- [AOSP Android 16 AudioAttributes](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android16-release/media/java/android/media/AudioAttributes.java)
- [AOSP Android 16 AudioService](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android16-release/services/core/java/com/android/server/audio/AudioService.java)
- [Xposed API](https://api.xposed.info/)
- [LSPosed 文档](https://github.com/LSPosed/LSPosed/wiki)
