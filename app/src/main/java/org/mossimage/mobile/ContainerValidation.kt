package org.mossimage.mobile

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/** Structural extent checks detect truncated containers; authenticity still needs a publisher digest. */
object ContainerValidation {
    private fun RandomAccessFile.u32() = Integer.reverseBytes(readInt()).toLong() and 0xffffffffL
    private fun RandomAccessFile.u64(): Long = java.lang.Long.reverseBytes(readLong()).also { require(it >= 0) { "GGUF integer overflow" } }
    private fun RandomAccessFile.skip(n: Long) {
        require(n >= 0 && n <= length()-filePointer) { "模型文件被截断" }; seek(filePointer+n)
    }
    private fun RandomAccessFile.string(): String {
        val n=u64(); require(n <= 1_048_576 && n <= length()-filePointer)
        return ByteArray(n.toInt()).also { readFully(it) }.toString(Charsets.UTF_8)
    }
    private fun RandomAccessFile.skipValue(type: Int, depth: Int = 0) {
        require(depth < 8)
        when(type) {
            0,1,7 -> skip(1)
            2,3 -> skip(2)
            4,5,6 -> skip(4)
            10,11,12 -> skip(8)
            8 -> skip(u64())
            9 -> {
                val element=u32().toInt(); val count=u64()
                require(count <= length()-filePointer)
                repeat(count.toInt()) { skipValue(element,depth+1) }
            }
            else -> error("Unsupported GGUF metadata type $type")
        }
    }
    fun gguf(file: File) {
        RandomAccessFile(file,"r").use { r ->
            require(r.length() >= 24) { "GGUF 头不完整" }
            require(r.readInt() == 0x47475546) { "不是 GGUF" }
            require(r.u32() in 2L..3L) { "仅支持 GGUF v2/v3" }
            val tensors=r.u64(); val metadata=r.u64()
            require(tensors in 1..1_000_000 && metadata <= 1_000_000)
            var alignment=32L
            repeat(metadata.toInt()) {
                val key=r.string(); val type=r.u32().toInt()
                if(key=="general.alignment") { require(type==4); alignment=r.u32() }
                else r.skipValue(type)
            }
            require(alignment in 1..65536 && alignment and (alignment-1)==0L)
            val extents=ArrayList<Pair<Long,Long>>()
            // block elements -> block bytes, matching pinned GGML types. Reject unreviewed types explicitly.
            val layouts=mapOf(0 to (1 to 4),1 to (1 to 2),2 to (32 to 18),3 to (32 to 20),
                6 to (32 to 22),7 to (32 to 24),8 to (32 to 34),9 to (32 to 36),
                10 to (256 to 84),11 to (256 to 110),12 to (256 to 144),13 to (256 to 176),
                14 to (256 to 210),15 to (256 to 292),30 to (1 to 2))
            repeat(tensors.toInt()) {
                r.string(); val dims=r.u32().toInt(); require(dims in 1..4)
                val shape=LongArray(dims) { r.u64().also { require(it>0) } }
                val type=r.u32().toInt(); val offset=r.u64()
                val layout=requireNotNull(layouts[type]) { "尚未审查的 GGUF tensor type $type；请使用 Q2_K/Q3_K/Q4_K 或 F16/F32/BF16" }
                require(shape[0]%layout.first==0L)
                var bytes=Math.multiplyExact(shape[0]/layout.first,layout.second.toLong())
                for(i in 1 until dims) bytes=Math.multiplyExact(bytes,shape[i])
                require(offset%alignment==0L)
                extents.add(offset to Math.addExact(offset,bytes))
            }
            val dataStart=Math.addExact(r.filePointer,alignment-1)/alignment*alignment
            var previous=0L
            for((start,end) in extents.sortedBy { it.first }) {
                require(start>=previous && end<=r.length()-dataStart) { "GGUF tensor 数据被截断或重叠" }
                previous=end
            }
        }
    }
    fun safetensors(file: File) {
        RandomAccessFile(file,"r").use { r ->
            require(r.length()>8)
            val n=r.u64(); require(n in 2..100_000_000 && n<=r.length()-8) { "safetensors 头被截断" }
            val header=JSONObject(ByteArray(n.toInt()).also { r.readFully(it) }.toString(Charsets.UTF_8))
            val extents=ArrayList<Pair<Long,Long>>()
            val widths=mapOf("F64" to 8,"F32" to 4,"F16" to 2,"BF16" to 2,"I64" to 8,"I32" to 4,"I16" to 2,"I8" to 1,"U8" to 1,"BOOL" to 1,"F8_E4M3" to 1,"F8_E5M2" to 1)
            for(key in header.keys()) {
                if(key=="__metadata__") continue
                val tensor=header.getJSONObject(key)
                val offsets=tensor.getJSONArray("data_offsets"); require(offsets.length()==2)
                val start=offsets.getLong(0);val end=offsets.getLong(1)
                val shape=tensor.getJSONArray("shape")
                var bytes=requireNotNull(widths[tensor.getString("dtype")]) { "不支持的 VAE dtype" }.toLong()
                for(i in 0 until shape.length()) { val dim=shape.getLong(i);require(dim>=0); bytes=Math.multiplyExact(bytes,dim) }
                require(start>=0 && end>=start && end-start==bytes) { "safetensors shape/offset 不匹配" }
                extents.add(start to end)
            }
            require(extents.isNotEmpty())
            var previous=0L
            for((start,end) in extents.sortedBy { it.first }) {
                require(start==previous && end<=r.length()-8-n) { "safetensors 数据被截断或重叠" }; previous=end
            }
            require(previous==r.length()-8-n) { "safetensors 尾部数据不匹配" }
        }
    }
}
