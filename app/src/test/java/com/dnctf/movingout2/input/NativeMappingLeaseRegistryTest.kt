package com.dnctf.movingout2.input

import org.junit.Assert.*
import org.junit.Test

class NativeMappingLeaseRegistryTest {
    @Test fun samePathInstancesShareOneNativeMappingLeaseGroup() {
        val registry = NativeMappingLeaseRegistry()
        assertEquals(NativeMappingLeaseRegistry.AcquireResult.FIRST, registry.acquire("/data/user/0/app/xinput_shm.bin"))
        assertEquals(NativeMappingLeaseRegistry.AcquireResult.SHARED, registry.acquire("/data/user/0/app/xinput_shm.bin"))
        assertEquals(2, registry.activeLeaseCount())
        assertEquals(NativeMappingLeaseRegistry.ReleaseResult.RETAINED, registry.release("/data/user/0/app/xinput_shm.bin"))
        assertEquals(1, registry.activeLeaseCount())
        assertEquals(NativeMappingLeaseRegistry.ReleaseResult.LAST, registry.release("/data/user/0/app/xinput_shm.bin"))
        assertEquals(0, registry.activeLeaseCount())
        assertNull(registry.activePath())
    }

    @Test fun conflictingPathCannotReplaceAnActiveGlobalMapping() {
        val registry = NativeMappingLeaseRegistry()
        assertEquals(NativeMappingLeaseRegistry.AcquireResult.FIRST, registry.acquire("/private/one.bin"))
        assertEquals(NativeMappingLeaseRegistry.AcquireResult.CONFLICT, registry.acquire("/private/two.bin"))
        assertEquals("/private/one.bin", registry.activePath())
        assertEquals(1, registry.activeLeaseCount())
    }

    @Test fun newPathCanBeAcquiredAfterTheLastOwnerReleases() {
        val registry = NativeMappingLeaseRegistry()
        assertEquals(NativeMappingLeaseRegistry.AcquireResult.FIRST, registry.acquire("/private/one.bin"))
        assertEquals(NativeMappingLeaseRegistry.ReleaseResult.LAST, registry.release("/private/one.bin"))
        assertEquals(NativeMappingLeaseRegistry.AcquireResult.FIRST, registry.acquire("/private/two.bin"))
        assertEquals("/private/two.bin", registry.activePath())
    }

    @Test fun unknownReleaseDoesNotUnderflowAndFailedDestroyCanRestoreLease() {
        val registry = NativeMappingLeaseRegistry()
        assertEquals(NativeMappingLeaseRegistry.ReleaseResult.NOT_HELD, registry.release("/missing.bin"))
        assertEquals(NativeMappingLeaseRegistry.AcquireResult.FIRST, registry.acquire("/private/shared.bin"))
        assertEquals(NativeMappingLeaseRegistry.ReleaseResult.LAST, registry.release("/private/shared.bin"))
        assertTrue(registry.restore("/private/shared.bin"))
        assertEquals(1, registry.activeLeaseCount())
        assertEquals("/private/shared.bin", registry.activePath())
    }
}
