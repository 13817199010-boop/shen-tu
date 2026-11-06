package org.mossimage.mobile

import org.junit.Assert.*
import org.junit.Test

class GenerationConfigTest {
    @Test fun acceptsAllRequestedSizesAndThreadCounts() {
        for (size in listOf(256, 512, 768, 1024)) for (threads in listOf(4, 6, 8))
            assertNull(GenerationConfig(size, 50, 42, 3f, threads).error("中文猫咪", true))
    }
    @Test fun rejectsUnsafeRequests() {
        assertNotNull(GenerationConfig(257).error("cat", true))
        assertNotNull(GenerationConfig(0).error("cat", true))
        assertNotNull(GenerationConfig(2048).error("cat", true))
        assertNotNull(GenerationConfig(steps = 0).error("cat", true))
        assertNotNull(GenerationConfig(steps = 101).error("cat", true))
        assertNotNull(GenerationConfig(threads = 0).error("cat", true))
        assertNotNull(GenerationConfig(threads = 9).error("cat", true))
        assertNotNull(GenerationConfig(cfg = Float.NaN).error("cat", true))
        assertNotNull(GenerationConfig(cfg = -1f).error("cat", true))
        assertNotNull(GenerationConfig().error("  ", true))
        assertNotNull(GenerationConfig().error("cat", false))
    }
}
