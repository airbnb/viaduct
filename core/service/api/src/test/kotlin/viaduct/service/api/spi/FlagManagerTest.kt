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
    fun `FlagManager_default returns false for mat resolution`() {
        assertFalse(FlagManager.Default.isEnabled(Flags.ENABLE_MAT_RESOLUTION))
    }

    @Test
    fun `FlagManager_default returns false for incremental execution`() {
        assertFalse(FlagManager.Default.isEnabled(Flags.ENABLE_INCREMENTAL_EXECUTION))
    }

    @Test
    fun `FlagManager_default does not enable engine2`() {
        assertFalse(FlagManager.Default.isEnabled(Flags.ENGINE2_ENABLED))
    }

    @Test
    fun `FlagManager_default does not enable engine2 batching`() {
        assertFalse(FlagManager.Default.isEnabled(Flags.ENGINE2_BATCHING))
    }

    @Test
    fun `FlagManager_default does not enable field RSS origin filtering killswitch`() {
        assertFalse(FlagManager.Default.isEnabled(Flags.KILLSWITCH_FIELD_RSS_ORIGIN_FILTERING))
    }
}
