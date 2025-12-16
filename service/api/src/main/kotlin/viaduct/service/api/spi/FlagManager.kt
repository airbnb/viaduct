package viaduct.service.api.spi

import viaduct.apiannotations.InternalApi
import viaduct.service.api.spi.Flags.EXECUTE_ACCESS_CHECKS

/**
 * Interface for managing feature flags.
 */
@InternalApi
interface FlagManager {
    /**
     * Returns a boolean representing whether [flag] is enabled. Impl should execute very quickly as it could
     * be used in the hot path.
     */
    fun isEnabled(flag: Flag): Boolean

    object disabled : FlagManager {
        override fun isEnabled(flag: Flag): Boolean = false
    }

    object default : FlagManager {
        override fun isEnabled(flag: Flag): Boolean =
            when (flag) {
                EXECUTE_ACCESS_CHECKS -> true

                else -> false
            }
    }
}
