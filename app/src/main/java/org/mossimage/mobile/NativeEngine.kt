package org.mossimage.mobile

object NativeEngine {
    init { System.loadLibrary("moss_jni") }
    external fun generate(directory: ByteArray, prompt: ByteArray, size: Int, steps: Int,
                          seed: Long, cfg: Float, threads: Int, listener: InferenceService): ByteArray
    external fun prepare()
    external fun cancel()
}
