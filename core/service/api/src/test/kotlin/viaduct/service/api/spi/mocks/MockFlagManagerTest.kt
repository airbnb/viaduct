@file:Suppress("ForbiddenImport")

package viaduct.service.api.spi.mocks

import io.kotest.property.Arb
import io.kotest.property.arbitrary.of
import io.kotest.property.forAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import viaduct.service.api.spi.FlagManager
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
    fun create(): Unit =
        runBlocking {
            val allFlags = Flags.values().toList()
            val enabledFlags = allFlags.take(1).toSet()
            val flagMgr = MockFlagManager(enabledFlags)
            allFlags.forEach { flag ->
                assert(flagMgr.isEnabled(flag) == enabledFlags.contains(flag))
            }
        }

    @Test
    fun `enabling ordinary flags does not disable field mat resolution`() {
        assertFalse(MockFlagManager.RuntimeFlagsEnabled.isFieldMatResolutionDisabled("Query", "foo"))
        assertFalse(MockFlagManager.Enabled.isFieldMatResolutionDisabled("Query", "foo"))
    }

    @Test
    fun `runtime flags exclude construction-time gates`() {
        val constructionTimeFlags =
            setOf(
                Flags.ENGINE2_ENABLED,
                Flags.ENGINE2_PER_REQUEST,
                Flags.ENGINE2_BATCHING,
            )

        Flags.values().forEach { flag ->
            assertEquals(
                flag !in constructionTimeFlags,
                MockFlagManager.RuntimeFlagsEnabled.isEnabled(flag),
            )
        }
    }

    @ParameterizedTest(name = "engine2={0}, delegateEnabled={1}")
    @CsvSource("false, false", "false, true", "true, false", "true, true")
    fun `engine selection overrides delegate selection and preserves other flags`(
        engine2Enabled: Boolean,
        delegateEnabled: Boolean,
    ) {
        val flags = MockFlagManager.forEngine(engine2Enabled, MockFlagManager.const(delegateEnabled))

        assertEquals(engine2Enabled, flags.isEnabled(Flags.ENGINE2_ENABLED))
        assertFalse(flags.isEnabled(Flags.ENGINE2_PER_REQUEST))
        Flags.values().filterNot { it == Flags.ENGINE2_ENABLED || it == Flags.ENGINE2_PER_REQUEST }.forEach { flag ->
            assertEquals(delegateEnabled, flags.isEnabled(flag), flag.flagName)
        }
    }

    @ParameterizedTest(name = "engine2={0}")
    @ValueSource(booleans = [false, true])
    fun `engine selection defaults unrelated flags to disabled`(engine2Enabled: Boolean) {
        val flags = MockFlagManager.forEngine(engine2Enabled)

        assertEquals(engine2Enabled, flags.isEnabled(Flags.ENGINE2_ENABLED))
        Flags.values().filterNot { it == Flags.ENGINE2_ENABLED }.forEach { flag ->
            assertFalse(flags.isEnabled(flag), flag.flagName)
        }
    }

    @Test
    fun `engine selection preserves live runtime flags and field exclusions`() {
        var incrementalEnabled = false
        val delegate = object : FlagManager {
            override fun isEnabled(flag: Flag): Boolean = flag == Flags.ENABLE_INCREMENTAL_EXECUTION && incrementalEnabled

            override fun isFieldMatResolutionDisabled(
                typeName: String,
                fieldName: String,
            ): Boolean = typeName == "Query" && fieldName == "excluded"
        }
        val flags = MockFlagManager.forEngine(engine2Enabled = true, delegate = delegate)

        assertFalse(flags.isEnabled(Flags.ENABLE_INCREMENTAL_EXECUTION))
        incrementalEnabled = true
        assertTrue(flags.isEnabled(Flags.ENABLE_INCREMENTAL_EXECUTION))
        assertTrue(flags.isFieldMatResolutionDisabled("Query", "excluded"))
        assertFalse(flags.isFieldMatResolutionDisabled("Query", "ordinary"))
        assertFalse(flags.isFieldMatResolutionDisabled("Other", "excluded"))
    }

    private fun Arb.Companion.flag(): Arb<Flag> = Arb.of(Flags.values().toList())
}
