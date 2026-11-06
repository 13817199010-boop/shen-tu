package org.mossimage.mobile

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object AtomicImport {
    fun copy(input: InputStream, target: File, progress: (Long) -> Unit, validate: (File) -> Unit): String {
        val temp = File(target.parentFile, target.name + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            input.use { source ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var count = 0L
                    while (true) {
                        check(!Thread.currentThread().isInterrupted) { "导入已取消" }
                        val n = source.read(buffer)
                        if(n < 0) break
                        check(target.parentFile!!.usableSpace > n + 64L * 1024 * 1024) { "存储不足" }
                        output.write(buffer, 0, n); digest.update(buffer, 0, n); count += n
                        progress(count)
                    }
                    output.fd.sync()
                }
            }
            validate(temp)
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            check(temp.renameTo(target)) { "无法提交导入文件" }
            return sha
        } finally { temp.delete() }
    }
}
