package viaduct.service.api.spi

import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi
import viaduct.apiannotations.StableApi
import viaduct.service.api.spi.FlagManager.Flags.EXECUTE_ACCESS_CHECKS

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

    /**
     * Represents a feature flag with a name.
     *
     * This interface is sealed to discourage external implementations. Use [Flags] for framework-defined flags.
     */
    @StableApi
    sealed interface Flag {
        val flagName: String
    }

    @ExperimentalApi
    enum class Flags(
        override val flagName: String
    ) : Flag {
        EXECUTE_ACCESS_CHECKS("execute_access_checks_in_modern_execution_strategy"),
        DISABLE_QUERY_PLAN_CACHE("disable_query_plan_cache"),
        KILLSWITCH_NON_BLOCKING_ENQUEUE_FLUSH("common.kotlin.nextTickDispatcher.killswitch.nonBlockingEnqueueFlush"),
        ENABLE_SUBQUERY_EXECUTION_VIA_HANDLE("enable_subquery_execution_via_handle"),
    }
}
