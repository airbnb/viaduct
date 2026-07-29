package viaduct.service.api.spi

import viaduct.apiannotations.StableApi

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
    object Disabled : FlagManager {
        override fun isEnabled(flag: Flag): Boolean = false
    }

    /** A [FlagManager] that uses the framework-default state for each flag. */
    @StableApi
    object Default : FlagManager {
        override fun isEnabled(flag: Flag): Boolean = false
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
        /** Enables the Mat-based resolver workflow, which differentially executes resolvers */
        ENABLE_MAT_RESOLUTION("enable_mat_resolution"),

        /** Killswitch for non-blocking enqueue flush in the coroutine dispatcher. */
        KILLSWITCH_NON_BLOCKING_ENQUEUE_FLUSH("common.kotlin.nextTickDispatcher.killswitch.nonBlockingEnqueueFlush"),

        /**
         * Killswitch for origin filtering on field-level RSS child plans in `CollectFields`.
         * When killswitch enabled, falling back to the legacy permissive filter that
         * does not check the origin coordinate of attached child plans.
         * Default to be false (i.e., new origin-coordinate filter is on).
         */
        KILLSWITCH_FIELD_RSS_ORIGIN_FILTERING("killswitch.field_rss_origin_filtering"),
    }
}
