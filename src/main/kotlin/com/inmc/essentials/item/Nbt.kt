package com.inmc.essentials.item

import java.io.ByteArrayOutputStream
import java.io.DataInput
import java.io.DataInputStream
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 작은 NBT 읽기·쓰기(순수 — 서버 클래스를 쓰지 않는다). `/검색` 이 접속하지 않은 사람의 `players/data/<uuid>.dat` 를 **읽기만** 하려고 둔다.
 *
 * 값: Byte·Short·Int·Long·Float·Double·ByteArray·String·[NbtList]·`Map<String, Any>`(compound)·IntArray·LongArray.
 * 아이템 하나는 compound 그대로 떼어 `DataVersion` 을 붙여 다시 압축하면 Paper `ItemStack.deserializeBytes` 가 읽는 모양이 된다
 * (Paper 의 serializeAsBytes = 이름 없는 루트 compound + DataVersion 을 gzip — DataFixer 가 그 판에서 지금 판으로 올린다).
 */
object Nbt {

    data class NbtList(val type: Int, val items: List<Any>)

    /** gzip 된 NBT 파일 → 루트 compound. 루트 이름은 버린다. */
    fun readCompressed(input: InputStream): Map<String, Any> =
        DataInputStream(GZIPInputStream(input).buffered()).use { read(it) }

    fun read(input: DataInput): Map<String, Any> {
        val type = input.readUnsignedByte()
        require(type == TAG_COMPOUND) { "루트가 compound 가 아닙니다($type)" }
        input.readUTF()
        @Suppress("UNCHECKED_CAST")
        return payload(input, type, 0) as Map<String, Any>
    }

    fun readFile(file: File): Map<String, Any> = file.inputStream().use { readCompressed(it) }

    /** compound → gzip 된 NBT(루트 이름 ""). */
    fun writeCompressed(root: Map<String, Any>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(bytes)).use { out ->
            out.writeByte(TAG_COMPOUND)
            out.writeUTF("")
            writePayload(out, root)
        }
        return bytes.toByteArray()
    }

    private fun payload(input: DataInput, type: Int, depth: Int): Any {
        require(depth < 512) { "NBT 가 너무 깊습니다" }
        return when (type) {
            1 -> input.readByte()
            2 -> input.readShort()
            3 -> input.readInt()
            4 -> input.readLong()
            5 -> input.readFloat()
            6 -> input.readDouble()
            7 -> ByteArray(length(input)).also { input.readFully(it) }
            8 -> input.readUTF()
            9 -> {
                val element = input.readUnsignedByte()
                val n = length(input)
                NbtList(element, List(n) { payload(input, element, depth + 1) })
            }
            TAG_COMPOUND -> {
                val map = LinkedHashMap<String, Any>()
                while (true) {
                    val t = input.readUnsignedByte()
                    if (t == 0) break
                    val key = input.readUTF()
                    map[key] = payload(input, t, depth + 1)
                }
                map
            }
            11 -> IntArray(length(input)) { input.readInt() }
            12 -> LongArray(length(input)) { input.readLong() }
            else -> throw IllegalArgumentException("모르는 NBT 종류 $type")
        }
    }

    private fun length(input: DataInput): Int = input.readInt().also { require(it in 0..MAX_LENGTH) { "NBT 길이가 이상합니다($it)" } }

    fun typeOf(value: Any): Int = when (value) {
        is Byte -> 1
        is Short -> 2
        is Int -> 3
        is Long -> 4
        is Float -> 5
        is Double -> 6
        is ByteArray -> 7
        is String -> 8
        is NbtList -> 9
        is Map<*, *> -> TAG_COMPOUND
        is IntArray -> 11
        is LongArray -> 12
        else -> throw IllegalArgumentException("NBT 로 쓸 수 없는 값: ${value.javaClass}")
    }

    private fun writePayload(out: DataOutput, value: Any) {
        when (value) {
            is Byte -> out.writeByte(value.toInt())
            is Short -> out.writeShort(value.toInt())
            is Int -> out.writeInt(value)
            is Long -> out.writeLong(value)
            is Float -> out.writeFloat(value)
            is Double -> out.writeDouble(value)
            is ByteArray -> { out.writeInt(value.size); out.write(value) }
            is String -> out.writeUTF(value)
            is NbtList -> {
                out.writeByte(if (value.items.isEmpty()) 0 else value.type)
                out.writeInt(value.items.size)
                for (item in value.items) writePayload(out, item)
            }
            is Map<*, *> -> {
                for ((k, v) in value) {
                    if (k !is String || v == null) continue
                    out.writeByte(typeOf(v))
                    out.writeUTF(k)
                    writePayload(out, v)
                }
                out.writeByte(0)
            }
            is IntArray -> { out.writeInt(value.size); value.forEach { out.writeInt(it) } }
            is LongArray -> { out.writeInt(value.size); value.forEach { out.writeLong(it) } }
            else -> throw IllegalArgumentException("NBT 로 쓸 수 없는 값: ${value.javaClass}")
        }
    }

    const val TAG_COMPOUND = 10
    private const val MAX_LENGTH = 16 * 1024 * 1024
}

/**
 * 플레이어 데이터 파일(`players/data/<uuid>.dat`, 옛 `playerdata/`) 하나에서 아이템만 뽑는다 — **읽기만**(파일을 고치지 않는다).
 * 각 아이템은 `ItemStack.deserializeBytes` 에 바로 넣을 수 있는 바이트로.
 */
object PlayerDataFile {

    /** [where] = 인벤·엔더·장비. */
    data class Entry(val where: String, val bytes: ByteArray)

    data class Data(val dataVersion: Int, val gameType: Int?, val items: List<Entry>)

    fun read(root: Map<String, Any>): Data {
        val version = (root["DataVersion"] as? Int) ?: 0
        val out = ArrayList<Entry>()
        fun add(where: String, raw: Any?) {
            @Suppress("UNCHECKED_CAST")
            val compound = (raw as? Map<String, Any>)?.let { LinkedHashMap(it) } ?: return
            if (compound["id"] == null) return
            compound.remove("Slot")
            compound["DataVersion"] = version
            out += Entry(where, Nbt.writeCompressed(compound))
        }
        (root["Inventory"] as? Nbt.NbtList)?.items?.forEach { item ->
            val slot = ((item as? Map<*, *>)?.get("Slot") as? Byte)?.toInt()
            add(if (slot != null && (slot in 100..103 || slot == -106)) WHERE_EQUIPMENT else WHERE_INVENTORY, item)
        }
        (root["EnderItems"] as? Nbt.NbtList)?.items?.forEach { add(WHERE_ENDER, it) }
        (root["equipment"] as? Map<*, *>)?.values?.forEach { add(WHERE_EQUIPMENT, it) }
        return Data(version, root["playerGameType"] as? Int, out)
    }

    /** 이 서버의 플레이어 데이터 폴더들(26.x `players/data`, 옛 `playerdata`). */
    fun folders(worldFolder: File): List<File> =
        listOf(File(worldFolder, "players/data"), File(worldFolder, "playerdata")).filter { it.isDirectory }

    const val WHERE_INVENTORY = "인벤"
    const val WHERE_ENDER = "엔더"
    const val WHERE_EQUIPMENT = "장비"
}
