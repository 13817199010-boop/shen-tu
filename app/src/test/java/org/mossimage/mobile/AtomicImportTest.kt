package org.mossimage.mobile

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.file.Files

class AtomicImportTest {
    @Test fun failedImportPreservesExistingModelAndRemovesPartialFile() {
        val dir = Files.createTempDirectory("moss-import").toFile()
        try {
            val target = File(dir,"model.gguf").apply { writeText("original") }
            val broken = object: InputStream() {
                var n=0
                override fun read(): Int { if(n++ < 4) return 7; error("provider lost connection") }
            }
            assertThrows(IllegalStateException::class.java) { AtomicImport.copy(broken,target,{},{}) }
            assertEquals("original",target.readText())
            assertFalse(File(dir,"model.gguf.part").exists())
        } finally { dir.deleteRecursively() }
    }
    @Test fun validatesBeforeReplaceAndComputesRealDigest() {
        val dir = Files.createTempDirectory("moss-import").toFile()
        try {
            val target = File(dir,"model").apply { writeText("original") }
            assertThrows(IllegalArgumentException::class.java) {
                AtomicImport.copy("abc".byteInputStream(),target,{}, { require(it.length()>5) })
            }
            assertEquals("original",target.readText())
            val hash = AtomicImport.copy("abc".byteInputStream(),target,{}, {})
            assertEquals("abc",target.readText())
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",hash)
        } finally { dir.deleteRecursively() }
    }
}
