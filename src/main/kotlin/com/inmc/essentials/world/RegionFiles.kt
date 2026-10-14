package com.inmc.essentials.world

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * 지역 파일(`r.<x>.<z>.mca`) 읽기 전용 검사(순수 — 서버 없이 돈다, `RegionFilesTest`). `/청크복구` 가 워커 스레드에서 쓴다.
 *
 * 서버(Moonrise)는 청크를 읽다 실패하면 "chunk data will be lost" 를 남기고 **그 청크를 새로 만든다**(leaf-26.2.jar
 * `ChunkLoadTask$ChunkDataLoadTask` · `MoonriseRegionFileIO` 문자열로 확인). 그래서 여기서 찾는 "손상" 은 서버가 못 읽을 모양 그대로다 —
 * 머리표가 파일 밖을 가리킴 · 길이가 이상함 · 압축이 안 풀림 · NBT 구조가 깨짐 · 청크 칸이 없음.
 *
 * 서버가 쓰는 중인 파일을 읽으므로 한 번의 실패는 찢어진 읽기일 수 있다 — 부르는 쪽이 잠시 뒤 한 번 더 확인한다.
 */
object RegionFiles {

    sealed interface Result {
        data object Ok : Result
        data object Missing : Result
        data class Damaged(val reason: String) : Result
        data class Unsupported(val reason: String) : Result
    }

    const val SECTOR = 4096
    const val HEADER = 8192

    private val NAME = Regex("""^r\.(-?\d+)\.(-?\d+)\.mca$""")

    /** 파일 이름 → 지역 좌표. */
    fun regionOf(name: String): Pair<Int, Int>? = NAME.matchEntire(name)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

    /** 한 지역 파일 안의 청크 [index](0..1023, x = i and 31, z = i shr 5) 검사. */
    fun check(file: File, index: Int): Result = RandomAccessFile(file, "r").use { raf -> check(raf, file, index) }

    /** 같은 파일을 여러 번 볼 때 — [raf] 를 열어 둔 채. */
    fun check(raf: RandomAccessFile, file: File, index: Int): Result {
        val length = raf.length()
        if (length < HEADER) return if (length == 0L) Result.Missing else Result.Damaged("머리표가 잘림(${length}바이트)")
        raf.seek(index * 4L)
        val loc = raf.readInt()
        if (loc == 0) return Result.Missing
        val offset = loc ushr 8
        val count = loc and 0xff
        val sectors = (length + SECTOR - 1) / SECTOR
        if (offset < 2 || count == 0 || offset + count > sectors) return Result.Damaged("머리표가 파일 밖을 가리킴(칸 $offset+$count / $sectors)")
        raf.seek(offset.toLong() * SECTOR)
        val size = raf.readInt()
        // 바닐라의 "큰 청크"(압축 바이트의 128 표시)는 길이 1 + 압축 바이트만 남고 본문은 .mcc 파일에 있다.
        if (size <= 0 || size > count * SECTOR - 4) return Result.Damaged("길이가 이상함($size / ${count * SECTOR})")
        val compression = raf.readUnsignedByte()
        val external = compression and 0x80 != 0
        val type = compression and 0x7f
        val body: ByteArray = if (external) {
            val region = regionOf(file.name) ?: return Result.Unsupported("파일 이름을 읽지 못함")
            val cx = region.first * 32 + (index and 31)
            val cz = region.second * 32 + (index shr 5)
            val mcc = File(file.parentFile, "c.$cx.$cz.mcc")
            if (!mcc.isFile) return Result.Damaged("바깥 파일(${mcc.name})이 없음")
            mcc.readBytes()
        } else {
            ByteArray(size - 1).also { raf.readFully(it) }
        }
        val input = try {
            decompress(type, ByteArrayInputStream(body)) ?: return Result.Unsupported("압축 방식 $type")
        } catch (t: Throwable) {
            return Result.Damaged("압축을 풀지 못함(${t.javaClass.simpleName})")
        }
        return input.use { validateChunk(it) }
    }

    /** 1 gzip · 2 zlib · 3 없음 · 4 LZ4(서버가 품은 lz4-java 를 리플렉션으로 — 없으면 null) · 그 밖 null. */
    fun decompress(type: Int, raw: InputStream): InputStream? = when (type) {
        1 -> GZIPInputStream(raw)
        2 -> InflaterInputStream(raw)
        3 -> raw
        4 -> runCatching {
            Class.forName("net.jpountz.lz4.LZ4BlockInputStream").getConstructor(InputStream::class.java).newInstance(raw) as InputStream
        }.getOrNull()
        else -> null
    }

    /** NBT 를 끝까지 걸어 본다. 루트가 이름 있는 묶음이고 청크 칸(`Status`·`sections`·`xPos` 중 하나)이 있어야 성공. */
    fun validateChunk(input: InputStream): Result {
        val keys = try {
            Nbt.walkRoot(DataInputStream(input))
        } catch (_: EOFException) {
            return Result.Damaged("NBT 가 중간에 끊김")
        } catch (t: Throwable) {
            return Result.Damaged("NBT 를 읽지 못함(${t.message ?: t.javaClass.simpleName})")
        }
        if (keys.none { it == "Status" || it == "sections" || it == "xPos" || it == "Level" }) return Result.Damaged("청크 칸이 없음")
        return Result.Ok
    }

    /** 최소한의 NBT 걷기 — 값은 버리고 구조만 확인한다(바닐라 형식, 깊이 512 까지). */
    object Nbt {

        private const val MAX_DEPTH = 512
        private const val MAX_ARRAY = 64 * 1024 * 1024

        /** 루트 묶음의 맨 윗 칸 이름들. 루트가 묶음이 아니면 던진다. */
        fun walkRoot(input: DataInputStream): List<String> {
            val type = input.readUnsignedByte()
            if (type != 10) throw IllegalStateException("루트가 묶음이 아님($type)")
            skipString(input)
            val names = ArrayList<String>()
            while (true) {
                val t = input.readUnsignedByte()
                if (t == 0) break
                names += input.readUTF()
                skipPayload(input, t, 1)
            }
            return names
        }

        private fun skipString(input: DataInputStream) {
            skip(input, input.readUnsignedShort().toLong())
        }

        private fun skip(input: DataInputStream, n: Long) {
            var left = n
            while (left > 0) {
                val s = input.skip(left)
                if (s <= 0) {
                    input.readByte() // EOF 면 여기서 던진다
                    left--
                } else {
                    left -= s
                }
            }
        }

        private fun length(input: DataInputStream, unit: Int): Long {
            val n = input.readInt()
            if (n < 0 || n.toLong() * unit > MAX_ARRAY) throw IllegalStateException("배열 길이가 이상함($n)")
            return n.toLong() * unit
        }

        fun skipPayload(input: DataInputStream, type: Int, depth: Int) {
            if (depth > MAX_DEPTH) throw IllegalStateException("너무 깊음")
            when (type) {
                1 -> skip(input, 1)
                2 -> skip(input, 2)
                3, 5 -> skip(input, 4)
                4, 6 -> skip(input, 8)
                7 -> skip(input, length(input, 1))
                8 -> skipString(input)
                9 -> {
                    val inner = input.readUnsignedByte()
                    val n = input.readInt()
                    if (n < 0) throw IllegalStateException("목록 길이가 음수")
                    if (n > 0 && inner == 0) throw IllegalStateException("빈 종류의 목록")
                    repeat(n) { skipPayload(input, inner, depth + 1) }
                }
                10 -> while (true) {
                    val t = input.readUnsignedByte()
                    if (t == 0) break
                    skipString(input)
                    skipPayload(input, t, depth + 1)
                }
                11 -> skip(input, length(input, 4))
                12 -> skip(input, length(input, 8))
                else -> throw IllegalStateException("모르는 종류 $type")
            }
        }
    }
}
