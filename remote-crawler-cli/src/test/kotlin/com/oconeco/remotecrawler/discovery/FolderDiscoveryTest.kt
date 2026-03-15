package com.oconeco.remotecrawler.discovery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class FolderDiscoveryTest {

    @Test
    fun `special linux pseudo roots should be included but not descended`() {
        val discovery = FolderDiscovery()

        listOf("/proc", "/run", "/sys", "/dev", "/lost+found").forEach { path ->
            val policy = discovery.specialFolderPolicy(path)
            assertNotNull(policy, "expected special policy for $path")
            assertFalse(policy.descend, "expected $path to stop descent")
            assertEquals(SuggestedStatus.SKIP, policy.suggestedStatus)
        }
    }

    @Test
    fun `boot and snap should not be hard excluded roots`() {
        val discovery = FolderDiscovery()

        assertNull(discovery.specialFolderPolicy("/boot"))
        assertNull(discovery.specialFolderPolicy("/snap"))
        assertNull(discovery.specialFolderPolicy("/root"))
    }
}
