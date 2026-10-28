package viaduct.service.runtime

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.service.api.spi.FlagManager

class FrozenFlagManagerTest {
    @Test
    fun `field mat exclusions are delegated without freezing`() {
        var disabled = true
        val delegate = object : FlagManager {
            override fun isEnabled(flag: FlagManager.Flag): Boolean = false

            override fun isFieldMatResolutionDisabled(
                typeName: String,
                fieldName: String
            ): Boolean = disabled && typeName == "Query" && fieldName == "foo"
        }
        val subject = FrozenFlagManager(delegate, FlagManager.Flags.ENGINE2_ENABLED)

        assertTrue(subject.isFieldMatResolutionDisabled("Query", "foo"))
        assertFalse(subject.isFieldMatResolutionDisabled("Query", "bar"))
        assertFalse(subject.isFieldMatResolutionDisabled("Other", "foo"))

        disabled = false

        assertFalse(subject.isFieldMatResolutionDisabled("Query", "foo"))
    }

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
