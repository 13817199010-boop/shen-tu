package org.mossimage.mobile
import org.junit.Test
import org.junit.Assert.*
import java.nio.file.Files

class ModelLeaseTest {
    @Test fun refusesConcurrentMutationAndReleasesOnException() {
        val dir=Files.createTempDirectory("moss-lock").toFile()
        try {
            ModelLease.exclusive(dir) {
                assertThrows(IllegalStateException::class.java) { ModelLease.exclusive(dir) { fail("entered locked model store") } }
            }
            assertThrows(IllegalArgumentException::class.java) { ModelLease.exclusive(dir) { throw IllegalArgumentException("test") } }
            assertEquals(42, ModelLease.exclusive(dir) { 42 })
        } finally { dir.deleteRecursively() }
    }
}
