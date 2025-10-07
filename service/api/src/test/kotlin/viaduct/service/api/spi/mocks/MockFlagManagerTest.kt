@file:Suppress("ForbiddenImport")

package viaduct.service.api.spi.mocks

import io.kotest.property.Arb
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.take
import io.kotest.property.forAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import viaduct.service.api.spi.Flag

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
            val allFlags = Arb.flag()
            val enabledFlags = allFlags.take(100).toSet()
            val flagMgr = MockFlagManager(enabledFlags)
            allFlags.forAll { flag ->
                flagMgr.isEnabled(flag) == enabledFlags.contains(flag)
            }
        }
}

private data class MockFlag(override val flagName: String) : Flag

fun Arb.Companion.flag(): Arb<Flag> = Arb.string().map(::MockFlag)
