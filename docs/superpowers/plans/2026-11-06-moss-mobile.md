# MOSS Image Mobile Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a genuine CPU MOSS JNI APK with auditable limitations.
**Architecture:** Compose UI → Messenger foreground service in isolated process → JNI → pinned sdcpp/GGML. Private model copies and output PNGs.
**Tech Stack:** Kotlin 2.1.20, AGP 8.9.2, Gradle 8.13, SDK 36, NDK 28.2.13676358, CMake 3.22.1, arm64-v8a.
**Spec:** docs/superpowers/specs/2026-11-06-moss-mobile-design.md

## Global Constraints
Offline native inference; no Termux/root/PC needed by end user; no simulated results; upstream APIs only; CPU default; 16 GiB fit remains unverified.

## Review Focus
- Missing/truncated model files must fail before loading.
- Native errors must release context/results and restore UI controls.
- Cancel/load races must not dereference freed context.
- Multi-GB imports must stream and never overwrite a valid model on failure.
- App recreation/service disconnect must not leave a second concurrent worker.

### Task 1: Engine and build
- [x] Write GenerationConfigTest for sizes, threads, seed/CFG/steps and incomplete models; run and observe failure.
- [x] Implement GenerationConfig.validate and fixed upstream CMake/JNI CPU generation.
- [x] Compile arm64 library against real upstream headers, preserving GGML pin.

### Task 2: Android workflow
- [x] Implement streamed import with hashes and header checks, no inference on main thread.
- [x] Implement single-job service, actual callbacks, cancellation, cleanup, PNG write.
- [x] Compose controls, device diagnostics, preview/history and SAF export.
- [x] Build/test/lint; inspect packaged ABI and native alignment. Device-only verification remains explicitly unrun.

### Task 3: Delivery
- [x] GitHub Actions debug/release builds and artifact upload; optional private signing.
- [x] Audit references/licenses and document mobile-only use and device test matrix.
- [x] Commit source and provide artifact if build succeeds; save tested environment instructions.
