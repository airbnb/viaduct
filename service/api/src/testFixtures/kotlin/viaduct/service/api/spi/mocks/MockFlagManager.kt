package viaduct.service.api.spi.mocks

import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.FlagManager.Flag

class MockFlagManager(
    private val enabled: Set<Flag> = emptySet()
) : FlagManager {
    override fun isEnabled(flag: Flag): Boolean = enabled.contains(flag)

    companion object {
        fun mk(vararg flag: Flag): MockFlagManager = MockFlagManager(flag.toSet())

        fun const(enabled: Boolean): FlagManager =
            object : FlagManager {
                override fun isEnabled(flag: Flag): Boolean = enabled
            }

        val Enabled: FlagManager = const(true)
        val Disabled: FlagManager = const(false)
    }
}
