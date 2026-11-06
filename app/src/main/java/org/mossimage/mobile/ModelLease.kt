package org.mossimage.mobile

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException

object ModelLease {
    fun <T> exclusive(directory: File, action: () -> T): T {
        directory.mkdirs()
        return RandomAccessFile(File(directory,".models.lock"), "rw").use { file ->
            val lock = try { file.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            checkNotNull(lock) { "模型正用于导入或推理，请稍后再试" }
            lock.use { action() }
        }
    }
}
