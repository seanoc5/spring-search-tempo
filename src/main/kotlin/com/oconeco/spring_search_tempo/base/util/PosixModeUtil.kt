package com.oconeco.spring_search_tempo.base.util

import java.nio.file.attribute.PosixFilePermission

/**
 * Translates Java's `Set<PosixFilePermission>` into the standard 0-0777
 * POSIX mode integer (stored as decimal). Issue #120 — keeps the typed
 * mode column useful for cheap bitwise SQL questions like
 * "world-writable" (`posix_mode & 2 != 0`).
 *
 * Setuid/setgid/sticky bits aren't exposed by `PosixFilePermission`, so
 * those high bits are always zero.
 */
object PosixModeUtil {

    fun toMode(permissions: Set<PosixFilePermission>?): Int? {
        if (permissions == null) return null
        var mode = 0
        for (p in permissions) {
            mode = mode or when (p) {
                PosixFilePermission.OWNER_READ     -> 0b100_000_000
                PosixFilePermission.OWNER_WRITE    -> 0b010_000_000
                PosixFilePermission.OWNER_EXECUTE  -> 0b001_000_000
                PosixFilePermission.GROUP_READ     -> 0b000_100_000
                PosixFilePermission.GROUP_WRITE    -> 0b000_010_000
                PosixFilePermission.GROUP_EXECUTE  -> 0b000_001_000
                PosixFilePermission.OTHERS_READ    -> 0b000_000_100
                PosixFilePermission.OTHERS_WRITE   -> 0b000_000_010
                PosixFilePermission.OTHERS_EXECUTE -> 0b000_000_001
            }
        }
        return mode
    }

    /** Render the mode as the customary 4-digit octal string ("0755"). */
    fun toOctal(mode: Int?): String? =
        mode?.let { String.format("0%03o", it and 0b111_111_111) }
}
