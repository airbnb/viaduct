package viaduct.tenant.runtime.select

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.tenant.runtime.executioncontext.Foo

@ExperimentalCoroutinesApi
class SelectionSetFactoryImplTest : Assertions() {
    @Test
    fun `selectionsOn -- simple`() {
        val emptyEngineSelectionSet = mockk<viaduct.engine.api.EngineSelectionSet> {
            every { isTransitivelyEmpty() } returns true
        }
        val factory = SelectionSetFactoryImpl(
            mockk {
                every {
                    engineSelectionSet(any(), any(), any())
                } returns emptyEngineSelectionSet
            },
            GlobalIDCodecDefault,
        )

        val ss = factory.selectionsOn(Foo.Reflection, "id", emptyMap())
        assertTrue(ss.isEmpty())
    }

    @Test
    fun `selectionsOn -- normalizes variables`() {
        val emptyEngineSelectionSet = mockk<viaduct.engine.api.EngineSelectionSet> {
            every { isTransitivelyEmpty() } returns true
        }
        val variables = slot<Map<String, Any?>>()
        val factory = SelectionSetFactoryImpl(
            mockk {
                every {
                    engineSelectionSet(any(), any(), capture(variables))
                } returns emptyEngineSelectionSet
            },
            GlobalIDCodecDefault,
        )

        factory.selectionsOn(Foo.Reflection, "id", mapOf("status" to TestStatus.ACTIVE))

        assertEquals(mapOf("status" to "ACTIVE"), variables.captured)
    }

    private enum class TestStatus {
        ACTIVE,
    }
}
