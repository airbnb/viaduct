package viaduct.service.runtime

import viaduct.service.api.spi.FlagManager

/**
 * A [FlagManager] that snapshots the values of [frozenFlags] when it is constructed and delegates
 * all other flag lookups to [delegate].
 */
internal class FrozenFlagManager(
    private val delegate: FlagManager,
    vararg frozenFlags: FlagManager.Flag,
) : FlagManager {
    private val frozenValues = frozenFlags.toSet().associateWith(delegate::isEnabled)

    override fun isEnabled(flag: FlagManager.Flag): Boolean = frozenValues[flag] ?: delegate.isEnabled(flag)
}
