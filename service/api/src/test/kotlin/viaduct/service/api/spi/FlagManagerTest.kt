package viaduct.service.api.spi

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FlagManagerTest {
    @Test
    fun `FlagManager_disabled always returns false`() {
        Flags.values().forEach { flag ->
            assertFalse(FlagManager.disabled.isEnabled(flag))
        }

        val anonymousFlag = object : Flag {
            // flagName getter intentionally throws -- FlagManager
            //  should return false without accessing Flag.flagName
            override val flagName: String get() = TODO()
        }
        assertFalse(FlagManager.disabled.isEnabled(anonymousFlag))
    }

    @Test
    fun `FlagManager_default returns true for select flags`() {
        assertTrue(FlagManager.default.isEnabled(Flags.EXECUTE_ACCESS_CHECKS))
    }
}
