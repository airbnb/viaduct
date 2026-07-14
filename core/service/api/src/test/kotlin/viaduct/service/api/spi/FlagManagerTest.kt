package viaduct.service.api.spi

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import viaduct.service.api.spi.FlagManager.Flags

class FlagManagerTest {
    @Test
    fun `FlagManager_disabled always returns false`() {
        Flags.values().forEach { flag ->
            assertFalse(FlagManager.Disabled.isEnabled(flag))
        }
    }

    @Test
    fun `FlagManager_default returns false for selective oer keys`() {
        assertFalse(FlagManager.Default.isEnabled(Flags.ENABLE_SELECTIVE_OER_KEYS))
    }

    @Test
    fun `FlagManager_default returns false for mat resolution`() {
        assertFalse(FlagManager.Default.isEnabled(Flags.ENABLE_MAT_RESOLUTION))
    }

    @Test
    fun `FlagManager_default does not enable field RSS origin filtering killswitch`() {
        assertFalse(FlagManager.Default.isEnabled(Flags.KILLSWITCH_FIELD_RSS_ORIGIN_FILTERING))
    }
}
