package com.ignyx.core

/** Little-endian helpers. */
internal object Bytes {
    fun le16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    fun le32(b: ByteArray, at: Int): Long =
        (0 until 4).fold(0L) { acc, i -> acc or ((b[at + i].toLong() and 0xFF) shl (8 * i)) }

    fun le64(b: ByteArray, at: Int): Long =
        (0 until 8).fold(0L) { acc, i -> acc or ((b[at + i].toLong() and 0xFF) shl (8 * i)) }

    fun putLe16(b: ByteArray, at: Int, v: Int) {
        b[at] = (v and 0xFF).toByte()
        b[at + 1] = ((v shr 8) and 0xFF).toByte()
    }

    fun putLe32(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 4) b[at + i] = ((v shr (8 * i)) and 0xFF).toByte()
    }

    fun putLe64(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 8) b[at + i] = ((v shr (8 * i)) and 0xFF).toByte()
    }

    fun ceilDiv(a: Long, b: Long): Long = (a + b - 1) / b
}
