@file:Suppress("ForbiddenImport")

package viaduct.service.api.spi.mocks

import io.kotest.property.Arb
import io.kotest.property.arbitrary.of
import io.kotest.property.forAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import viaduct.service.api.spi.FlagManager.Flag
import viaduct.service.api.spi.FlagManager.Flags

class MockFlagManagerTest {
    @Test
    fun enabled(): Unit =
        runBlocking {
            Arb.flag().forAll { flag ->
                MockFlagManager.Enabled.isEnabled(flag)
            }
        }

    @Test
    fun disabled(): Unit =
        runBlocking {
            Arb.flag().forAll { flag ->
                !MockFlagManager.Disabled.isEnabled(flag)
            }
        }

    @Test
    fun mk(): Unit =
        runBlocking {
            val allFlags = Flags.values().toList()
            val enabledFlags = allFlags.take(1).toSet()
            val flagMgr = MockFlagManager(enabledFlags)
            allFlags.forEach { flag ->
                assert(flagMgr.isEnabled(flag) == enabledFlags.contains(flag))
            }
        }

    private fun Arb.Companion.flag(): Arb<Flag> = Arb.of(Flags.values().toList())
}
