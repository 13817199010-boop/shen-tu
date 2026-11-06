# MOSS Image Mobile — CPU milestone

User requests direct implementation from an empty repository. Primary target is OPPO Pad 5, reported Dimensity 9400+, 16 GiB physical RAM, Android 16; these are user-supplied, not device-verified.

Use Kotlin/Compose with a JNI adapter to stable-diffusion.cpp f5df5f8b84f139f1e80496902da90798a0d92896 and its exact GGML submodule. Alternatives: reuse Flutter Local-Diffusion (extra runtime, no established MOSS support), or port official Python (incompatible with standalone native requirement). Native Kotlin is the engineering choice within the requested stack.

App imports four components through SAF into private filesystem files for mmap. Import is streamed, atomic and hashed; no multi-GB byte arrays. No network permission or online inference in this CPU milestone. Foreground inference service runs in a separate process: native OOM must not take down the UI; logs and actual step counts travel through Messenger. Serialize generation; release context and result on every completion. Cancel uses upstream cancellation during compute; loading may require terminating the isolated process if unresponsive. CPU only, no unverified GPU promises.

Use 256 initial smoke resolution, sizes divisible by 16, Euler/discrete, shift 5, CFG 3, 50 steps, 4 threads as conservative unbenchmarked default. mmap, lazy loading, conditioning cache disabled and VAE tiling enabled. No claim these fit 16 GiB: physical free memory, activation peaks and LMKD need device measurements. Output real RGB pixels converted to PNG, private history and SAF export. Show measured elapsed time, service PSS, system available memory, actual device/build info and thermal status where supported.

Scope delivered first: import, CPU inference, logs/progress/cancel, history/export, build automation, release signing hook. Download/resume, custom model directory, Vulkan, LoRA, background removal, upscale and benchmarks remain later milestones and must be disclosed. App must visibly warn that the build has not yet been validated with models on the target device. No synthetic output/progress.

Validation: request-boundary unit tests, JNI/NDK compilation and APK assembly/lint where tooling permits. No available Android physical device or model weights: no device inference, quality, RAM-fit or repeated-generation claims. CI uploads debug and unsigned release APK; private signing variables enable signed release without exposing keys. Do not publish to GitHub without authenticated write authorization/tool support.
