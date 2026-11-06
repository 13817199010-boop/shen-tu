package org.mossimage.mobile
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class ContainerValidationTest {
    private fun gguf(): ByteArray {
        val out=ByteArrayOutputStream()
        fun int(n: Int) { out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array()) }
        fun long(n: Long) { out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(n).array()) }
        out.write("GGUF".toByteArray()); int(3); long(1); long(0)
        long(1); out.write('x'.code); int(1); long(4); int(0); long(0)
        while(out.size()%32!=0) out.write(0)
        out.write(ByteArray(16)); return out.toByteArray()
    }
    @Test fun rejectsTruncatedGGUFDespiteValidMagicAndTensorDirectory() {
        val file=Files.createTempFile("model","gguf").toFile()
        try {
            val bytes=gguf(); file.writeBytes(bytes); ContainerValidation.gguf(file)
            file.writeBytes(bytes.copyOf(bytes.size-1))
            assertThrows(IllegalArgumentException::class.java) { ContainerValidation.gguf(file) }
        } finally {file.delete()}
    }
    @Test fun rejectsTruncatedSafetensorsAndInvalidShape() {
        val file=Files.createTempFile("model","safetensors").toFile()
        try {
            val header="""{"x":{"dtype":"F32","shape":[4],"data_offsets":[0,16]}}""".toByteArray()
            val bytes=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(header.size.toLong()).array()+header+ByteArray(16)
            file.writeBytes(bytes); ContainerValidation.safetensors(file)
            file.writeBytes(bytes.copyOf(bytes.size-1))
            assertThrows(IllegalArgumentException::class.java) { ContainerValidation.safetensors(file) }
        } finally {file.delete()}
    }
}
