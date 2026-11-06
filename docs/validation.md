# 验证记录 — 2026-11-06

当前状态：**构建通过、实机推理未验证**。

- 官方 Android SDK36、NDK r28c 28.2.13676358、CMake 3.22.1、JDK17、Gradle8.13 已安装；Gradle 和 JDK 校验官方 SHA256，Android CLI 校验官方仓库 SHA1。安装/网络全过程保留 TLS 验证。
- ARM64 CMake 独立编译通过，随后 AGP 外部 CMake 集成编译通过。JNI 链接真实 stable-diffusion.cpp f5df5f8b84f139f1e80496902da90798a0d92896 与其固定 GGML135ce1e0e3f2b0693d3de93d4f6b5fa0a1947820。
- 最终命令 `./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease --console=plain` 成功。7 tests、0 failures、0 errors、0 skipped。覆盖参数边界、原子导入失败保护、真实 SHA256、跨任务互斥和异常释放、GGUF/safetensors 截断拒绝。
- Android lint：0 errors、5 warnings：arm64-only 的 ChromeOS ABI、Android12 备份规则建议、两处保守可用存储判断、尚未自定义应用图标。未禁用规则。
- APK Debug 有 v2 开发签名，`apksigner verify` 通过；`zipalign -c -P 16 4` 通过。APK 包含 arm64-v8a 的真实 libmoss_jni.so、libc++_shared.so 和 AndroidX graphics path 库；JNI ELF LOAD alignment 为0x4000。
- 最终 Manifest 无 INTERNET 权限；无远程推理客户端、Python、Termux、Root 或虚假生成路径。
- Debug 可供安装测试；Release 无生产密钥，产出 unsigned APK，仅供后续正式签名。正式签名能力的环境变量入口已配置，但未使用用户私钥验证。
- 首次 Gradle 失败源于代理参数和系统只有 JRE；配置 JVM 官方代理参数并安装完整 JDK 后恢复。NDK 安装中途的 source.properties 缺失在安装完成后消失。许可证文件在构建时加入造成资产增量索引重复，重跑 mergeReleaseAssets 后恢复，最终整体验证成功。
- 独立审查发现的截断容器替换、SAF 导入目标重建和上游取消重置窗口已修正。Activity 重建和取消时机仍需设备测试，不将代码审查当作设备验证。

## 没有执行/没有证据的验收项

无 OPPO Pad 5 或 Android 设备连接；没有下载多 GB 模型。未执行 App 安装/启动测试、模型真实加载、CPU 图像生成、PNG 画质检查、16GB 峰值内存验证、连续生成泄漏检查、取消/后台恢复、4/6/8 线程测速、768/1024 可用性或 Vulkan 测试。APK 构建成功不能证明这些通过。

具体设备测试步骤见 [device-test.md](device-test.md)。下一步必须用 Debug APK 和完整四组件在真实平板上完成 256 CPU 生成，再推进内存、速度与 GPU 阶段。下载管理/外部模型目录/放大/抠图/LoRA 等后续需求未实现，详见 README。

本地 APK、校验和、构建日志和报告保存在 `/workspace/artifacts`。GitHub Actions 工作流已配置；仅远端实际运行成功才会生成 GitHub artifact，不把本机日志写成 Actions 已执行。

## GitHub Actions 首次运行

推送触发 run 42183395733；checkout、固定子模块、Java 成功，第三方 `android-actions/setup-android@v3` 初始化步骤失败（19s，未进入编译）。未登录页面不提供具体日志，未推断为源码问题。工作流已改用 Ubuntu runner 预装 Android SDK 的 sdkmanager，并直接使用仓库已校验的 Gradle Wrapper，移除这两个冗余 bootstrap action；Java/checkout 更新为 Node24 版本。云端重跑结果以 Actions 页面为准。
