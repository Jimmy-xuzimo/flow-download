# 首版验证记录

验证日期：2026-11-10。版本：0.1.0，minSdk 29 / targetSdk 36。

## 已完成

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| JVM 自动测试 | 28 项通过，0 失败 | `artifacts/verification/TEST-*.xml` |
| Android 10 手机设备测试 | 3 项通过，0 失败 | `artifacts/verification/android10-phone.txt` |
| Android 10 平板设备测试 | 3 项通过，0 失败 | `artifacts/verification/android10-tablet.txt` |
| Android 16 运行验证 | 未完成；软件模拟器系统级启动超时 | `artifacts/verification/android16-environment.txt` |
| APK 构建 | 成功 | `artifacts/verification/build.log` |
| Android lint | 0 错误；保留依赖升级与 KTX 风格建议 | `artifacts/verification/lint-results.xml` |
| APK 签名校验 | 通过，Android 调试签名 | `artifacts/verification/apk-signature.txt` |
| 手动测试服务自检 | 6 种场景通过 | 普通响应、未知大小、分段区间、完整数据及认证行为 |

JVM 测试覆盖：多连接分段与 SHA-256 一致性、不支持 Range 的回退、未知长度响应、零字节文件、真实取消后续传、文件版本变化、缺少校验标识时重新下载、错误 Content-Range、认证拒绝、过期链接、临时服务器错误、同站及跨站重定向、认证头隔离、已完成分段跳过、共享限速和安全输入处理。

5GB 文件测试验证的是 Long 长度与分段偏移计算，未执行完整 5GB 文件传输和导出。

最终 Android 10 回归使用同一 APK，分别运行于 AOSP x86_64 的手机窗口（720×1280、320dpi）与平板窗口（1280×800、160dpi），均为软件模拟器。每种窗口的三项设备测试分别验证：

1. 无效链接校验、打开设置及界面可操作性。
2. 2MB 固定数据的四连接下载、暂停后进度保持、继续、保存到 MediaStore、读取成品并比较 SHA-256，以及仅移除记录后文件仍可读取。
3. 已存在 Activity 接收分享 Intent 后，创建窗口正确预填链接。

设备测试曾定位并修复服务空闲退出的竞态：退出判断现经过任务命令队列和数据库确认；使用启动序号避免终止更新的启动请求，并用实例标识隔离旧服务的退出事件。

启动逻辑另已调整为空列表不启动前台服务、有待恢复任务时才自动启动，以及重复启动请求重新确认前台状态。最终版本在两种窗口尺寸下各通过完整回归。

此外，通过真实 Android 分享入口预填链接、点击“开始下载”，成功下载并导出 `tablet-demo.bin`；成品 SHA-256 为 `22a812e4a51b44871c0c47fd54345aeaa32d693158f21899db144de64f5a6f07`，与本地测试服务的预期值一致。

点击该任务后，已实测平板左侧列表与右侧详情同时显示；详情中的 100%、文件大小、保存位置和打开文件入口均正确显示。截图保存在 `artifacts/screenshots/android10-tablet-detail.png`。

Android 16 官方 AOSP 镜像在无 KVM 的软件模拟器中，SystemUI、电话服务、媒体提供器和启动器等组件反复 ANR；下载器的测试进程也在进入应用代码前的 `GraphicsEnvironment.setup` 阶段启动超时，被系统终止。该轮未执行测试用例，不计为通过，也不能据此确认应用在 Android 16 上的运行兼容性；需要在真机或硬件加速模拟器补验。

## 复现

```sh
./gradlew :core:test :app:assembleDebug :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

设备测试会删除本应用内的测试任务与测试文件，请使用独立测试设备或干净的应用数据。Android 13+ 测试时需先允许通知权限，避免系统权限窗口遮挡测试操作。

实机下载服务无需额外依赖：

```sh
python3 scripts/test_server.py --port 8765 --size 16777216
```

手机与电脑连接到同一局域网，下载 `http://电脑局域网IP:8765/demo.zip`。服务启动时输出预期 SHA-256。使用 `?mode=plain`、`unknown`、`wrong-range`、`expired`、`auth`、`disconnect` 验证不同响应。认证模式请求头为 `Authorization: Bearer local-test-token`。

## 真机验收仍需覆盖

- 不同厂商的锁屏、后台省电与强行停止后重新打开恢复。
- Wi-Fi 与移动网络实际切换、VPN 环境及仅 Wi-Fi 策略。
- 外置 SD 卡/不同文件提供器、授权失效、空间不足和完整的大于 4GB 文件。
- Android 前台服务时间配额触发、长时间运行，以及真实多窗口与大字体设置。

这些场景未以模拟器结果替代实机结论；当前 APK 用于首版功能测试，不包含正式发布签名。
