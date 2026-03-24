package viaduct.service.api.spi

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.service.api.spi.FlagManager.Flags

class FlagManagerTest {
    @Test
    fun `FlagManager_disabled always returns false`() {
        Flags.values().forEach { flag ->
            assertFalse(FlagManager.disabled.isEnabled(flag))
        }
    }

    @Test
    fun `FlagManager_default returns true for select flags`() {
        assertTrue(FlagManager.default.isEnabled(Flags.EXECUTE_ACCESS_CHECKS))
    }
}
