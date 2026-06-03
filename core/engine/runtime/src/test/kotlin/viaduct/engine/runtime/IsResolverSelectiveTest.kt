package viaduct.engine.runtime

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.Coordinate
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.runtime.mocks.createDispatcherRegistry

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
    fun `or returns true when first predicate returns true`() {
        val isResolverSelective = IsResolverSelective.const(true).or(IsResolverSelective.const(false))

        assertTrue(isResolverSelective(coordinate))
    }

    @Test
    fun `or returns true when second predicate returns true`() {
        val isResolverSelective = IsResolverSelective.const(false).or(IsResolverSelective.const(true))

        assertTrue(isResolverSelective(coordinate))
    }

    @Test
    fun `or returns false when both predicates return false`() {
        val isResolverSelective = IsResolverSelective.const(false).or(IsResolverSelective.const(false))

        assertFalse(isResolverSelective(coordinate))
    }

    @Test
    fun `from registry returns true for selective resolver`() {
        val dispatcherRegistry = dispatcherRegistryWithFieldResolver(isSelective = true)

        val isResolverSelective = IsResolverSelective.fromRegistry(
            dispatcherRegistry,
            selectiveResolverOerKeyingEnabled = true,
        )

        assertTrue(isResolverSelective(coordinate))
    }

    @Test
    fun `from registry returns false when resolver is missing`() {
        val isResolverSelective = IsResolverSelective.fromRegistry(
            DispatcherRegistry.Empty,
            selectiveResolverOerKeyingEnabled = true,
        )

        assertFalse(isResolverSelective(coordinate))
    }

    @Test
    fun `from registry returns false when selective resolver keying is disabled`() {
        val dispatcherRegistry = dispatcherRegistryWithFieldResolver(isSelective = true)

        val isResolverSelective = IsResolverSelective.fromRegistry(
            dispatcherRegistry,
            selectiveResolverOerKeyingEnabled = false,
        )

        assertFalse(isResolverSelective(coordinate))
    }

    @Test
    fun `from registry returns false for non-selective resolver`() {
        val dispatcherRegistry = dispatcherRegistryWithFieldResolver(isSelective = false)

        val isResolverSelective = IsResolverSelective.fromRegistry(
            dispatcherRegistry,
            selectiveResolverOerKeyingEnabled = true,
        )

        assertFalse(isResolverSelective(coordinate))
    }

    private fun dispatcherRegistryWithFieldResolver(isSelective: Boolean): DispatcherRegistry =
        createDispatcherRegistry(
            fieldResolverExecutors = mapOf(
                coordinate to MockFieldUnbatchedResolverExecutor(
                    resolverId = "test-resolver",
                    isSelective = isSelective,
                )
            )
        )
}
