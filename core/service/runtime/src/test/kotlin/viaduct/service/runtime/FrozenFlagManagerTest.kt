package viaduct.service.runtime

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.service.api.spi.FlagManager

class FrozenFlagManagerTest {
    @Test
    fun `freezes selected flags and delegates the rest`() {
        val enabled =
            mutableSetOf<FlagManager.Flag>(
                FlagManager.Flags.ENGINE2_ENABLED,
                FlagManager.Flags.ENABLE_MAT_RESOLUTION,
            )
        val delegate =
            object : FlagManager {
                override fun isEnabled(flag: FlagManager.Flag): Boolean = flag in enabled
            }
        val subject =
            FrozenFlagManager(
                delegate,
                FlagManager.Flags.ENGINE2_ENABLED,
                FlagManager.Flags.ENGINE2_BATCHING,
            )

        enabled.remove(FlagManager.Flags.ENGINE2_ENABLED)
        enabled.add(FlagManager.Flags.ENGINE2_BATCHING)
        enabled.remove(FlagManager.Flags.ENABLE_MAT_RESOLUTION)

        assertTrue(subject.isEnabled(FlagManager.Flags.ENGINE2_ENABLED))
        assertFalse(subject.isEnabled(FlagManager.Flags.ENGINE2_BATCHING))
        assertFalse(subject.isEnabled(FlagManager.Flags.ENABLE_MAT_RESOLUTION))

        enabled.add(FlagManager.Flags.ENABLE_MAT_RESOLUTION)

        assertTrue(subject.isEnabled(FlagManager.Flags.ENABLE_MAT_RESOLUTION))
    }
}
