# 上游技术审计（2026-11-06）

已读取各项目当前 README、入口实现及许可证；非核心项目仅调研，不引入 APK。

- KohakuMossTrainer: `78416a26f947ea075feb1c4d48db5f069d515c02`
- Local-Diffusion: `184b7f92cf2f810e7d5eb4b04b190a5da829005f`
- MOSS-Image: `1961ca3d111b828e65278656cde0c8f94484f4dd`
- Real-ESRGAN: `a4abfb2979a7bbff3f69f58f58ae324608821e27`
- ggml: `57e2c2ca2a7cae756b1c4dfafa0cdfef9051d234`
- phone-gpu-diffusion: `10a6c7776ad48de66849da000cbfe620738310e5`
- rembg: `991927b77667946dab59c50df31f7d4640e80457`
- stable-diffusion.cpp: `f5df5f8b84f139f1e80496902da90798a0d92896`

## 结论
- MOSS 官方 README：5B DiT + Qwen3.5-9B + FLUX.2 VAE；官方示例 50 步、CFG 3、shift 5。SFT 优先。官方 Python 内存统计不可替代 Android 测量。
- sdcpp docs/moss_image.md 和 src/model/integrations/moss_image.cpp：Base/SFT 支持，外部 tokenizer 是必须项。src/pipeline image 生成路径在负 CFG 分支跳过第 22 层；应由上游实现，JNI 不再重复处理。尺寸 divisible by 16。
- include/stable-diffusion.h：sd_ctx_params_init/new_sd_ctx/free_sd_ctx，sd_img_gen_params_init/generate_image/free_sd_image_generation_result，sd_set_progress_callback/sd_cancel_generation 都为当前真实 API。该版本不再使用旧的 txt2img 返回裸数组接口。
- 采用该提交内置 GGML 135ce1e0e3f2b0693d3de93d4f6b5fa0a1947820，不替换为另行审查的 ggml-org 最新版。
- NDK 需要 Bionic ARM64 原生编译；CMake 关闭 examples、WebP、WebM、CUDA、RPC、Vulkan，避免无关依赖。原生库按 16KiB page size 链接，r28 的 libc++ 支持 16KiB。
- Local-Diffusion 实际为 Flutter，Apache-2.0，不能直接把其 UI 当成 Compose 工程；仅参考模型分离、SAF 和任务生命周期。其模型列表不证明 MOSS 可运行。
- phone-gpu-diffusion 当前没有 LICENSE 文件，且使用 Termux/proot、修改 vocab 与驱动路径；不复制代码、不删 tokenizer。其 README 明确 CPU 正常而 Mali GPU 噪声/黑图，不能当成 Immortalis-G925 已验证方案。
- GGML MIT；sdcpp MIT；MOSS 代码 Apache-2.0；Local-Diffusion Apache-2.0；Real-ESRGAN BSD-3-Clause；rembg MIT；KohakuMossTrainer 为 KohakuMoss License 1.0（Apache 基础 + 命名附加条款），不是纯 Apache。未复用后者训练代码。模型权重许可证需分别核对，APK 不分发权重。
- Real-ESRGAN Python/PyTorch 非移动依赖；后续评估其 ncnn Android 路线。rembg 使用 ONNX 生态，后续评估 ONNX Runtime Android + 合法模型，不嵌入 Python。LoRA 暂仅保留上游能力，不暴露未验证控制。

## 模型来源（手工下载后 SAF 导入）
- https://huggingface.co/leejet/MOSS-Image-SFT-GGUF/tree/main — Q3_K 优先，必要时 Q2_K。
- https://huggingface.co/unsloth/Qwen3.5-9B-GGUF/tree/main — Q3_K_S 优先。
- https://huggingface.co/Comfy-Org/MOSS-Image/tree/main/vae — FLUX.2 VAE。
- https://huggingface.co/OpenMOSS-Team/MOSS-Image-Base/tree/main/tokenizer — tokenizer.json。
模型链接来自上游文档；本环境没有下载权重，未验证具体文件大小、官方校验和或量化质量。导入时解析 GGUF tensor 范围和 safetensors shape/data_offsets 检测结构截断，并记录本地 SHA256 指纹，不能声称已经证明文件完整或所有张量兼容。

## 风险与处理
- OOM/LMKD：独立推理进程、单任务、mmap/lazy、无 conditioning cache、VAE tiling、完成后销毁上下文；仍不保证 16GB 可运行。需测峰值 RSS/PSS、oom_score_adj 和系统日志。
- 组件分阶段释放、Flash Attention：接口存在部分优化，但当前仅启用经 API 确认的 mmap/lazy 和 tiling。未证明 Android TE/DiT/VAE 生命周期峰值，不能承诺自动满足内存上限。
- 取消：原生取消标志适用于计算；加载时可能等待。强制停止仅终止本 App 推理进程，UI 报告失败并允许重试。
- 黑图/NaN：CPU 固定 seed 对照官方或高精度版本；GPU 不编译、不默认，不以未测试的 fallback 冒充 GPU 支持。
- 模型下载/断点续传、发行方 hash 校验、自定义外部模型目录未实现；第一阶段用浏览器下载 + SAF 导入。无网络权限，推理无远程路径。
