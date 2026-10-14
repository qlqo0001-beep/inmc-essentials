package com.inmc.essentials.world

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.DeflaterOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** 지역 파일 검사 — 손으로 만든 `.mca` 로(서버 없이). */
class RegionFilesTest {

    /** 청크다운 NBT — 루트 묶음에 xPos·zPos·Status·sections(빈 목록)·heightmaps(long 배열). */
    private fun chunkNbt(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeByte(10); out.writeUTF("")
            out.writeByte(3); out.writeUTF("xPos"); out.writeInt(0)
            out.writeByte(3); out.writeUTF("zPos"); out.writeInt(0)
            out.writeByte(8); out.writeUTF("Status"); out.writeUTF("minecraft:full")
            out.writeByte(9); out.writeUTF("sections"); out.writeByte(10); out.writeInt(1)
            out.writeByte(1); out.writeUTF("Y"); out.writeByte(-4)
            out.writeByte(0)
            out.writeByte(10); out.writeUTF("Heightmaps")
            out.writeByte(12); out.writeUTF("WORLD_SURFACE"); out.writeInt(2); out.writeLong(1); out.writeLong(2)
            out.writeByte(0)
            out.writeByte(0)
        }
        return bytes.toByteArray()
    }

    private fun zlib(data: ByteArray): ByteArray = ByteArrayOutputStream().also { b -> DeflaterOutputStream(b).use { it.write(data) } }.toByteArray()

    /** 칸 0 에 [payload](압축 바이트 [type])를 넣은 지역 파일. [header] 로 머리표를 덮어쓸 수 있다. */
    private fun region(payload: ByteArray, type: Int = 2, header: Int? = null): File {
        val dir = Files.createTempDirectory("region").toFile()
        val file = File(dir, "r.0.0.mca")
        val sectors = (payload.size + 5 + 4095) / 4096
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeInt(header ?: ((2 shl 8) or sectors))
            repeat(1023) { d.writeInt(0) }
            repeat(1024) { d.writeInt(0) }
            d.writeInt(payload.size + 1)
            d.writeByte(type)
            d.write(payload)
            val pad = sectors * 4096 - (payload.size + 5)
            repeat(pad) { d.writeByte(0) }
        }
        file.writeBytes(out.toByteArray())
        return file
    }

    @Test
    fun `멀쩡한 청크`() {
        assertEquals(RegionFiles.Result.Ok, RegionFiles.check(region(zlib(chunkNbt())), 0))
        assertEquals(RegionFiles.Result.Ok, RegionFiles.check(region(chunkNbt(), type = 3), 0))
    }

    @Test
    fun `없는 칸은 손상이 아니다`() {
        assertEquals(RegionFiles.Result.Missing, RegionFiles.check(region(zlib(chunkNbt())), 5))
    }

    @Test
    fun `머리표가 파일 밖을 가리키면 손상`() {
        assertIs<RegionFiles.Result.Damaged>(RegionFiles.check(region(zlib(chunkNbt()), header = (40 shl 8) or 1), 0))
        assertIs<RegionFiles.Result.Damaged>(RegionFiles.check(region(zlib(chunkNbt()), header = (1 shl 8) or 1), 0))
    }

    @Test
    fun `압축이 안 풀리거나 NBT 가 깨지면 손상`() {
        assertIs<RegionFiles.Result.Damaged>(RegionFiles.check(region(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)), 0))
        val cut = chunkNbt().copyOf(chunkNbt().size - 20)
        assertIs<RegionFiles.Result.Damaged>(RegionFiles.check(region(zlib(cut)), 0))
        val notCompound = byteArrayOf(8, 0, 0, 0, 1, 65)
        assertIs<RegionFiles.Result.Damaged>(RegionFiles.check(region(zlib(notCompound)), 0))
    }

    @Test
    fun `모르는 압축은 확인 못 함`() {
        assertIs<RegionFiles.Result.Unsupported>(RegionFiles.check(region(chunkNbt(), type = 99), 0))
    }

    @Test
    fun `파일 이름에서 지역 좌표`() {
        assertEquals(-1 to 2, RegionFiles.regionOf("r.-1.2.mca"))
        assertEquals(null, RegionFiles.regionOf("r.0.0.linear"))
    }
}
