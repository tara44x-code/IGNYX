package com.ignyx.app

import java.util.Locale

/**
 * Wraps left-to-right text (file names, sizes) in Unicode isolates so it keeps its
 * order inside right-to-left Arabic text: "x32v1.1" instead of "x32v1 .1".
 */
fun ltr(s: String): String = "⁦$s⁩"

fun formatBytes(bytes: Long): String {
    val gb = bytes / 1_000_000_000.0
    val text = when {
        gb >= 1 -> String.format(Locale.US, "%.1f GB", gb)
        bytes >= 1_000_000 -> String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format(Locale.US, "%.0f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }
    return ltr(text)
}

fun formatSpeed(bytesPerSecond: Double): String =
    ltr(String.format(Locale.US, "%.1f MB/s", bytesPerSecond / 1_000_000.0))

/** "3 د 20 ث" style remaining time. */
fun formatDuration(seconds: Long): String {
    if (seconds < 0) return "…"
    val m = seconds / 60
    val s = seconds % 60
    return if (m > 0) "$m د $s ث" else "$s ث"
}

/** Compares "1.1.18" style versions. */
fun compareVersions(a: String, b: String): Int {
    val pa = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
    val pb = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val c = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
        if (c != 0) return c
    }
    return 0
}

val BOOT_EXTENSIONS = listOf(".iso", ".img", ".wim", ".vhd", ".vhdx", ".efi", ".vtoy")

fun isBootable(name: String) = BOOT_EXTENSIONS.any { name.lowercase().endsWith(it) }
