package viaduct.service.api.spi.mocks

import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.FlagManager.Flag

/**
 * Test-only [FlagManager] backed by a fixed set of enabled [Flag] values.
 *
 * Construct with an explicit [enabled] set, or use the [companion][Companion] factory helpers:
 * - [create] to enable a specific list of flags.
 * - [const] / [Enabled] / [Disabled] for globally-on or globally-off managers.
 *
 * @property enabled The flags that [isEnabled] will return `true` for.
 */
class MockFlagManager(
    private val enabled: Set<Flag> = emptySet()
) : FlagManager {
    override fun isEnabled(flag: Flag): Boolean = enabled.contains(flag)

    companion object {
        /** Returns a [MockFlagManager] that enables exactly the specified [flag] values. */
        fun create(vararg flag: Flag): MockFlagManager = MockFlagManager(flag.toSet())

        /**
         * Returns a [FlagManager] that returns [enabled] for every flag, regardless of which
         * flag is queried. Use [Enabled] or [Disabled] for the common all-on / all-off cases.
         */
        fun const(enabled: Boolean): FlagManager =
            object : FlagManager {
                override fun isEnabled(flag: Flag): Boolean = enabled
            }

        /** A [FlagManager] that reports every flag as enabled. */
        val Enabled: FlagManager = const(true)

        /** A [FlagManager] that reports every flag as disabled. */
        val Disabled: FlagManager = const(false)
    }
}
