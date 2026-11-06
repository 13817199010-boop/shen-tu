package org.mossimage.mobile

data class GenerationConfig(val size: Int = 256, val steps: Int = 50, val seed: Long = 42,
                            val cfg: Float = 3f, val threads: Int = 4) {
    fun error(prompt: String, modelsComplete: Boolean): String? = when {
        !modelsComplete -> "请先导入四个模型组件"
        prompt.isBlank() || '\u0000' in prompt -> "提示词不能为空或包含 NUL 字符"
        size !in 256..1024 || size % 16 != 0 -> "尺寸须为 256–1024 且为 16 的倍数"
        steps !in 1..100 -> "步数须为 1–100"
        !cfg.isFinite() || cfg !in 0f..20f -> "CFG 须为 0–20 的有限数值"
        threads !in 1..8 -> "CPU 线程须为 1–8"
        else -> null
    }
}
