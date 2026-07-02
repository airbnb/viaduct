package viaduct.remote.registry

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import viaduct.remote.fixtures.SimpleFieldResolverExecutor

class FieldExecutorRegistryTest {
    @AfterEach
    fun cleanup() {
        FieldExecutorRegistry.clear()
    }

    @Test
    fun `register returns the resolver id and stores the executor`() {
        val executor = SimpleFieldResolverExecutor(resolverId = "Character.isAdult")

        val handle = FieldExecutorRegistry.register(executor)

        assertEquals("Character.isAdult", handle)
        assertSame(executor, FieldExecutorRegistry.get(handle))
    }

    @Test
    fun `get returns null for an unregistered id`() {
        assertNull(FieldExecutorRegistry.get("Character.unknown"))
    }

    @Test
    fun `re-registering the same id overwrites the previous executor`() {
        val first = SimpleFieldResolverExecutor(resolverId = "Character.isAdult")
        val second = SimpleFieldResolverExecutor(resolverId = "Character.isAdult")

        FieldExecutorRegistry.register(first)
        FieldExecutorRegistry.register(second)

        assertSame(second, FieldExecutorRegistry.get("Character.isAdult"))
    }

    @Test
    fun `unregister removes and returns the executor`() {
        val executor = SimpleFieldResolverExecutor(resolverId = "Character.isAdult")
        FieldExecutorRegistry.register(executor)

        assertSame(executor, FieldExecutorRegistry.unregister("Character.isAdult"))
        assertNull(FieldExecutorRegistry.get("Character.isAdult"))
    }

    @Test
    fun `unregister returns null for an unknown id`() {
        assertNull(FieldExecutorRegistry.unregister("Character.unknown"))
    }

    @Test
    fun `clear removes all entries`() {
        FieldExecutorRegistry.register(SimpleFieldResolverExecutor(resolverId = "Character.isAdult"))
        FieldExecutorRegistry.register(SimpleFieldResolverExecutor(resolverId = "Character.isMinor"))

        FieldExecutorRegistry.clear()

        assertNull(FieldExecutorRegistry.get("Character.isAdult"))
        assertNull(FieldExecutorRegistry.get("Character.isMinor"))
    }
}
