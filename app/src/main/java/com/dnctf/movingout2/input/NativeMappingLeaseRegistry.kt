package com.dnctf.movingout2.input

/**
 * Pure Kotlin ownership registry for the JNI layer's process-global shared-memory mapping.
 * Only the first same-path lease initializes JNI; only the last lease destroys it.
 */
internal class NativeMappingLeaseRegistry {
    enum class AcquireResult { FIRST, SHARED, CONFLICT }
    enum class ReleaseResult { RETAINED, LAST, NOT_HELD }

    private var activePath: String? = null
    private var leases = 0

    @Synchronized
    fun acquire(path: String): AcquireResult {
        if (path.isBlank()) return AcquireResult.CONFLICT
        if (leases == 0) {
            activePath = path
            leases = 1
            return AcquireResult.FIRST
        }
        if (activePath != path) return AcquireResult.CONFLICT
        leases++
        return AcquireResult.SHARED
    }

    @Synchronized
    fun release(path: String): ReleaseResult {
        if (leases == 0 || activePath != path) return ReleaseResult.NOT_HELD
        leases--
        if (leases == 0) {
            activePath = null
            return ReleaseResult.LAST
        }
        return ReleaseResult.RETAINED
    }

    /** Restores a last lease if native unmapping fails, allowing a later destroy retry. */
    @Synchronized
    fun restore(path: String): Boolean {
        if (path.isBlank()) return false
        if (leases == 0) {
            activePath = path
            leases = 1
            return true
        }
        if (activePath != path) return false
        leases++
        return true
    }

    @Synchronized fun activeLeaseCount(): Int = leases
    @Synchronized fun activePath(): String? = activePath
}
