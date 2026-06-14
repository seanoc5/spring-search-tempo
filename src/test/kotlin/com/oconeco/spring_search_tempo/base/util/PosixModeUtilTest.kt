package com.oconeco.spring_search_tempo.base.util

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Unit tests for the PosixFilePermission → POSIX-mode integer
 * conversion used in the issue #120 crawl-side posix persistence.
 *
 * Mode integers are stored decimal but match the canonical octal
 * literal numerically: `0755` (octal) == `493` (decimal).
 */
@DisplayName("PosixModeUtil (issue #120)")
class PosixModeUtilTest {

    @Test
    @DisplayName("0755 (rwxr-xr-x) → decimal 493 (= octal 0755)")
    fun rwxRxRx() {
        val perms = PosixFilePermissions.fromString("rwxr-xr-x")
        val mode = PosixModeUtil.toMode(perms)
        assertThat(mode).isEqualTo(493) // 0755
        assertThat(PosixModeUtil.toOctal(mode)).isEqualTo("0755")
    }

    @Test
    @DisplayName("0644 (rw-r--r--) → decimal 420 (= octal 0644)")
    fun rwRR() {
        val perms = PosixFilePermissions.fromString("rw-r--r--")
        val mode = PosixModeUtil.toMode(perms)
        assertThat(mode).isEqualTo(420) // 0644
        assertThat(PosixModeUtil.toOctal(mode)).isEqualTo("0644")
    }

    @Test
    @DisplayName("world-writable bit test: posix_mode & 2 != 0")
    fun worldWritableBitTest() {
        // 0666 → others_write is set
        val worldWritable = PosixModeUtil.toMode(PosixFilePermissions.fromString("rw-rw-rw-"))!!
        assertThat(worldWritable and 0b010).isNotEqualTo(0)
        // 0644 → others_write is NOT set
        val safe = PosixModeUtil.toMode(PosixFilePermissions.fromString("rw-r--r--"))!!
        assertThat(safe and 0b010).isEqualTo(0)
    }

    @Test
    @DisplayName("null permission set returns null mode")
    fun nullPerms() {
        assertThat(PosixModeUtil.toMode(null)).isNull()
        assertThat(PosixModeUtil.toOctal(null)).isNull()
    }

    @Test
    @DisplayName("empty permission set is 0000")
    fun emptyPerms() {
        val mode = PosixModeUtil.toMode(emptySet<PosixFilePermission>())
        assertThat(mode).isEqualTo(0)
        assertThat(PosixModeUtil.toOctal(mode)).isEqualTo("0000")
    }
}
