package viaduct.service.api.spi

import viaduct.apiannotations.StableApi
import viaduct.service.api.spi.FlagManager.Flags.EXECUTE_ACCESS_CHECKS

/**
 * Interface for managing framework feature flags within the Viaduct runtime.
 *
 * Implementations are provided to [ViaductBuilder][viaduct.service.ViaductBuilder] via
 * `withFlagManager` and are queried on the hot path during query execution, so
 * [isEnabled] should return quickly.
 */
@StableApi
interface FlagManager {
    /**
     * Returns whether [flag] is enabled. Implementations should execute very quickly as this
     * is called on the hot path during query execution.
     */
    fun isEnabled(flag: Flag): Boolean

    /** A [FlagManager] that reports all flags as disabled. */
    @StableApi
    object disabled : FlagManager {
        override fun isEnabled(flag: Flag): Boolean = false
    }

    /** A [FlagManager] that uses the framework-default state for each flag. */
    @StableApi
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

    /** Framework-defined feature flags. */
    @StableApi
    enum class Flags(
        override val flagName: String
    ) : Flag {
        /** Controls whether access-check directives are enforced during execution. */
        EXECUTE_ACCESS_CHECKS("execute_access_checks_in_modern_execution_strategy"),

        /** Killswitch for non-blocking enqueue flush in the coroutine dispatcher. */
        KILLSWITCH_NON_BLOCKING_ENQUEUE_FLUSH("common.kotlin.nextTickDispatcher.killswitch.nonBlockingEnqueueFlush"),
        ENABLE_SYNC_VALUE_COMPUTATION("enable_sync_value_computation"),
    }
}
