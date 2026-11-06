# MOSS Image Mobile

原生 Android / Kotlin + Compose / JNI / C++ CPU 离线 MOSS-Image-SFT 生图工程。

**当前为实验首版，实机推理未验证。** 不声称已在 OPPO Pad 5 上成功生成、适配 16GB RAM 或通过稳定性/质量测试。构建结果请见 [验证记录](docs/validation.md)。不含模型权重，不调用云端生图；本版本无 INTERNET 权限。

## 在平板上使用（无需电脑、Root、Termux）
1. 在 GitHub 网页打开仓库的 Actions → Android CPU APK → Run workflow。首次推送该工作流后才可运行。完成后下载 `MOSS-Image-Mobile-CPU` artifact，解压并安装 debug APK，系统提示时允许浏览器安装。
2. 通过平板浏览器从 [模型来源与审计](docs/audit/upstream.md) 下载 MOSS SFT Q3_K、Qwen3.5-9B Q3_K_S、FLUX.2 VAE、MOSS tokenizer.json 四个文件。文件名不必重命名，在 App 内按对应组件依次导入。
3. 导入会流式复制到私有存储并计算本地 SHA-256。须预留模型副本 + 工作文件空间；本地指纹只用于追踪，不等同于下载完整性或可信来源校验。引擎检查张量/架构兼容性，日志中可能暴露错误组件信息。
4. 从 256×256、50 步、CFG 3、固定 Seed、4 线程开始。点击 CPU 生成；加载时只有不确定进度，采样时显示引擎实际步数。模型齐全后可以完全断网运行。
5. 真实生成结果显示在预览中；点击“保存 PNG”通过系统文件选择器导出。历史图保存在 App 私有目录，卸载会删除模型和历史，请先导出。
6. 取消请求由上游处理，加载阶段可能较慢。强制停止会终止本 App 的推理进程，保留主界面。系统回收/原生崩溃会报告服务退出，不能当成生成成功。

## 实现与限制
- 四组件导入/删除、CPU 生成、中文英文提示词、分辨率、步数/CFG/Seed、4/6/8 线程、真实回调、错误日志、PNG 预览/历史/SAF 导出。
- mmap/lazy loading、VAE 256 tile、禁用 conditioning cache、单任务，完成后销毁所有原生上下文。每次生成重新加载模型，以释放内存优先。
- 前台推理服务在独立进程中运行，持有最长 6h 的 wake lock。ColorOS 电池/后台策略仍可能终止任务；保持 App 在前台。前台服务 specialUse 在应用商店上架前需要政策审核。
- 系统物理 RAM/可用 RAM 和最近回调的推理进程 PSS 可查看；不是实际峰值。显示系统热状态，不伪造温度或 GPU 利用率。
- **未完成**：App 内下载与断点续传、官方 hash 校验、自定义模型目录、Vulkan 自动回退/混合推理、Flash Attention 验证、LoRA、透明背景、批量、超分。CPU-only 构建不提供虚假的 Vulkan 开关。
- 尚需真机：启动/权限、模型兼容性、真实 PNG、连续生成泄漏、取消、后台回收、4/6/8 线程性能、各尺寸内存峰值、固定 Seed 质量比较。16GB 物理内存是否足够仍未知。

## 开发构建

固定 Gradle 8.13、AGP 8.9.2、Kotlin 2.1.20、SDK 36、NDK 28.2.13676358、CMake 3.22.1、JDK 17+、arm64-v8a。依赖通过子模块固定到审计提交。无需初始化 CUDA、WebM 或前端子模块。

```sh
git submodule update --init --depth 1 vendor/stable-diffusion.cpp
git -C vendor/stable-diffusion.cpp submodule update --init --depth 1 ggml
# 设置 ANDROID_HOME 为 Android SDK 位置（或本地 local.properties sdk.dir）
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

云任务使用现有隔离 checkout；不要再创建 Git worktree。debug APK 有开发签名可安装；没有密钥时 release 为 unsigned，不能直接安装。正式签名在可信构建环境中设置 `MOSS_KEYSTORE`（文件路径）、`MOSS_STORE_PASSWORD`、`MOSS_KEY_ALIAS`、`MOSS_KEY_PASSWORD`，再执行 `./gradlew assembleRelease`。密钥和密码不得提交。GitHub Actions 默认仅构建 debug + unsigned release，签名步骤应在受保护工作流中配置私有 secrets，不对 fork PR 提供。

## 工程目录

- `app/src/main/java/org/mossimage/mobile/`：Compose、模型存储、IPC/前台服务、JNI 声明、请求校验。
- `app/src/main/cpp/`：真实 C API 适配、CPU 参数、生命周期与取消。
- `vendor/stable-diffusion.cpp`：固定上游与 GGML。
- `.github/workflows/android.yml`：云编译、测试、lint、产物。
- `docs/audit/`：来源、许可证、兼容性与风险。
- `docs/device-test.md`：真机验收记录模板。

APK 的 assets/licenses 包含复用代码的许可证。模型权重须遵守各自许可证。未复制 phone-gpu-diffusion（无 LICENSE）或 KohakuMossTrainer（含附加命名条款）的代码。
