package org.mossimage.mobile

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File

object ModelStore {
    val names = listOf("diffusion.gguf", "encoder.gguf", "vae.safetensors", "tokenizer.json")
    val labels = listOf("MOSS-Image-SFT GGUF", "Qwen3.5-9B GGUF", "FLUX.2 VAE safetensors", "MOSS tokenizer.json")
    fun directory(context: Context) = File(context.filesDir, "models").apply { mkdirs() }
    fun complete(context: Context) = names.all { File(directory(context), it).length() > 16 }

    // Caller serializes import against inference. Work only on the IO executor.
    fun import(context: Context, index: Int, uri: Uri, progress: (Long) -> Unit): String {
        require(index in names.indices)
        val dir = directory(context)
        val target = File(dir, names[index])
        return ModelLease.exclusive(dir) {
            val source = requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取文件" }
            val sha = AtomicImport.copy(source, target, progress) { temp ->
                check(temp.length() > 16) { "文件为空或过短" }
                if (index < 2) { ContainerValidation.gguf(temp)
                } else if (index == 2) { ContainerValidation.safetensors(temp)
                } else {
                    check(temp.length() < 64L * 1024 * 1024) { "tokenizer 文件异常过大" }
                    val json = JSONObject(temp.readText())
                    check(json.has("model")) { "无效 tokenizer.json" }
                }
            }
            File(dir, "${names[index]}.sha256").writeText(sha)
            "${names[index]}: ${target.length()} bytes\nSHA-256 $sha\n本地指纹已记录；尚未与发行方校验和比对。格式/组件兼容性将在引擎加载时检查。"
        }
    }
}
