package viaduct.engine.api.spi

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ResolverMetadata

class ProxyResolverFactoryTest {
    @Test
    fun `NO_OP proxyField returns null`() {
        val executor = mockk<FieldResolverExecutor>()
        assertNull(ProxyResolverFactory.NO_OP.proxyField(executor))
    }

    @Test
    fun `NO_OP proxyNode returns null`() {
        val executor = mockk<NodeResolverExecutor>()
        assertNull(ProxyResolverFactory.NO_OP.proxyNode(executor))
    }

    @Test
    fun `field resolver variable definitions default to empty`() {
        val executor = object : FieldResolverExecutor {
            override val objectSelectionSet: RequiredSelectionSet? = null
            override val querySelectionSet: RequiredSelectionSet? = null
            override val isSelective = false
            override val resolverId = "test-resolver"
            override val metadata = ResolverMetadata.forMock(resolverId)
            override val isBatching = false

            override suspend fun batchResolve(
                selectors: List<FieldResolverExecutor.Selector>,
                context: EngineExecutionContext,
            ): Map<FieldResolverExecutor.Selector, Result<Any?>> = emptyMap()
        }

        assertSame(VariableFromArgumentDefinitions.EMPTY, executor.argumentVariables)
        assertSame(VariableFromFieldDefinitions.EMPTY, executor.objectFieldVariables)
        assertSame(VariableFromFieldDefinitions.EMPTY, executor.queryFieldVariables)
        assertNull(executor.variablesFromFunctionProvider)
    }

    @Test
    fun `declarative variable definitions expose their variable names`() {
        val argumentVariables = VariableFromArgumentDefinitions(mapOf("argumentVariable" to "input.id"))
        val fieldVariables = VariableFromFieldDefinitions(mapOf("fieldVariable" to "object.id"))

        assertEquals("argumentVariable", argumentVariables.variableNames.single())
        assertEquals("fieldVariable", fieldVariables.variableNames.single())
        assertTrue(VariableFromArgumentDefinitions.EMPTY.variableNames.isEmpty())
        assertTrue(VariableFromFieldDefinitions.EMPTY.variableNames.isEmpty())
    }
}
