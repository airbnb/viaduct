package viaduct.engine.runtime

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.runtime.mocks.createDispatcherRegistry

class HasResolverTest {
    private val type = MockSchema.mk("extend type Query { testField: String }").schema.queryType

    @Test
    fun `Never always returns false`() {
        assertFalse(HasResolver.Never(type, "testField"))
    }

    @Test
    fun `Always always returns true`() {
        assertTrue(HasResolver.Always(type, "testField"))
    }

    @Test
    fun `const returns configured value`() {
        assertFalse(HasResolver.const(false)(type, "testField"))
        assertTrue(HasResolver.const(true)(type, "testField"))
    }

    @Test
    fun `or returns true when either check returns true`() {
        assertFalse((HasResolver.Never or HasResolver.Never)(type, "testField"))
        assertTrue((HasResolver.Never or HasResolver.Always)(type, "testField"))
        assertTrue((HasResolver.Always or HasResolver.Never)(type, "testField"))
        assertTrue((HasResolver.Always or HasResolver.Always)(type, "testField"))
    }

    @Test
    fun `from registry returns true for registered resolver`() {
        val hasResolver = HasResolver.fromRegistry(registryWithFieldResolver())

        assertTrue(hasResolver(type, "testField"))
    }

    @Test
    fun `from registry returns false for missing resolver`() {
        val hasResolver = HasResolver.fromRegistry(registryWithFieldResolver())

        assertFalse(hasResolver(type, "otherField"))
    }

    private fun registryWithFieldResolver(): DispatcherRegistry =
        createDispatcherRegistry(
            fieldResolverExecutors = mapOf(
                ("Query" to "testField") to MockFieldUnbatchedResolverExecutor.Null
            )
        )
}
