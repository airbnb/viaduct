@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime

import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.Coordinate

class IsResolverSelectiveTest {
    private val coordinate = Coordinate("TestType", "testField")

    @Test
    fun `Never always returns false`() {
        assertFalse(IsResolverSelective.Never(coordinate))
    }

    @Test
    fun `Always always returns true`() {
        assertTrue(IsResolverSelective.Always(coordinate))
    }

    @Test
    fun `const false always returns false`() {
        val isResolverSelective = IsResolverSelective.const(false)

        assertFalse(isResolverSelective(coordinate))
    }

    @Test
    fun `const true always returns true`() {
        val isResolverSelective = IsResolverSelective.const(true)

        assertTrue(isResolverSelective(coordinate))
    }

    @Test
    fun `from registry returns true for selective resolver`() {
        val dispatcher = mockk<FieldResolverDispatcher> {
            every { isSelective } returns true
        }
        val dispatcherRegistry = DispatcherRegistry.Impl(
            fieldResolverDispatchers = mapOf(coordinate to dispatcher),
            nodeResolverDispatchers = emptyMap(),
            fieldCheckerDispatchers = emptyMap(),
            typeCheckerDispatchers = emptyMap(),
        )

        val isResolverSelective = IsResolverSelective.fromRegistry(dispatcherRegistry)

        assertTrue(isResolverSelective(coordinate))
    }

    @Test
    fun `from registry returns false when resolver is missing`() {
        val isResolverSelective = IsResolverSelective.fromRegistry(DispatcherRegistry.Empty)

        assertFalse(isResolverSelective(coordinate))
    }
}
