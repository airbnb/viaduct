package viaduct.engine.runtime

import graphql.ExecutionResult
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLObjectType
import io.mockk.mockk
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.CompleteSelectionSetOptions
import viaduct.engine.api.Coordinate
import viaduct.engine.api.Engine
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.NodeReference
import viaduct.engine.api.ObjectEngineResult
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ResolveSelectionSetOptions
import viaduct.engine.api.ViaductSchema
import viaduct.engine.runtime.EngineExecutionContextExtensions.copy
import viaduct.engine.runtime.EngineExecutionContextExtensions.dataFetchingEnvironment
import viaduct.engine.runtime.EngineExecutionContextExtensions.dispatcherRegistry
import viaduct.engine.runtime.EngineExecutionContextExtensions.executeAccessChecksInModstrat
import viaduct.engine.runtime.EngineExecutionContextExtensions.hasResolver
import viaduct.engine.runtime.EngineExecutionContextExtensions.setExecutionHandle
import viaduct.engine.runtime.execution.ExecutionTestHelpers
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.service.api.spi.GlobalIDCodec

class EngineExecutionContextExtensionsTest {
    private val testSchema = ExecutionTestHelpers.createSchema(
        """
        type Query {
            foo: String
            bar: String
        }
        """.trimIndent(),
        resolvers = emptyMap()
    )

    private fun createContext(
        schema: ViaductSchema = testSchema,
        dispatcherRegistry: DispatcherRegistry = DispatcherRegistry.Empty
    ): EngineExecutionContextImpl {
        return ContextMocks(
            myFullSchema = schema,
            myDispatcherRegistry = dispatcherRegistry
        ).engineExecutionContextImpl
    }

    @Test
    fun `executeAccessChecksInModstrat delegates to impl`() {
        val context: EngineExecutionContext = createContext()
        val impl = context as EngineExecutionContextImpl

        assertEquals(impl.executeAccessChecksInModstrat, context.executeAccessChecksInModstrat)
    }

    @Test
    fun `dispatcherRegistry delegates to impl`() {
        val testDispatcherRegistry = DispatcherRegistry.Impl(
            fieldResolverDispatchers = emptyMap(),
            nodeResolverDispatchers = emptyMap(),
            fieldCheckerDispatchers = emptyMap(),
            typeCheckerDispatchers = emptyMap()
        )
        val context: EngineExecutionContext = createContext(dispatcherRegistry = testDispatcherRegistry)

        assertSame(testDispatcherRegistry, context.dispatcherRegistry)
    }

    @Test
    fun `resolverInstrumentation delegates to impl`() {
        val context: EngineExecutionContext = createContext()
        val impl = context as EngineExecutionContextImpl

        assertSame(impl.resolverInstrumentation, context.resolverInstrumentation)
    }

    @Test
    fun `dataFetchingEnvironment getter returns null initially`() {
        val context: EngineExecutionContext = createContext()

        assertNull(context.dataFetchingEnvironment)
    }

    @Test
    fun `dataFetchingEnvironment setter and getter work correctly`() {
        val context: EngineExecutionContext = createContext()
        val mockDfe = mockk<DataFetchingEnvironment>(relaxed = true)

        context.dataFetchingEnvironment = mockDfe

        assertSame(mockDfe, context.dataFetchingEnvironment)
    }

    @Test
    fun `fieldScopeSupplier delegates to impl`() {
        val context: EngineExecutionContext = createContext()
        val impl = context as EngineExecutionContextImpl

        assertSame(impl.fieldScopeSupplier, context.fieldScopeSupplier)
    }

    @Test
    fun `setExecutionHandle sets the internal handle`() {
        val context: EngineExecutionContext = createContext()
        val mockHandle = mockk<EngineExecutionContext.ExecutionHandle>()

        assertNull(context.executionHandle)
        context.setExecutionHandle(mockHandle)
        assertSame(mockHandle, context.executionHandle)
    }

    @Test
    fun `setExecutionHandle can set handle to null`() {
        val context: EngineExecutionContext = createContext()
        val mockHandle = mockk<EngineExecutionContext.ExecutionHandle>()

        context.setExecutionHandle(mockHandle)
        assertSame(mockHandle, context.executionHandle)

        context.setExecutionHandle(null)
        assertNull(context.executionHandle)
    }

    @Test
    fun `copy creates new instance with same properties`() {
        val context: EngineExecutionContext = createContext()
        val impl = context as EngineExecutionContextImpl

        val copied = context.copy()

        assertNotSame(context, copied)
        assertSame(impl.fullSchema, copied.fullSchema)
        assertSame(impl.scopedSchema, copied.scopedSchema)
        assertSame(impl.activeSchema, copied.activeSchema)
        assertSame(impl.dispatcherRegistry, copied.dispatcherRegistry)
        assertSame(impl.resolverInstrumentation, copied.resolverInstrumentation)
    }

    @Test
    fun `copy preserves executionHandle`() {
        val context: EngineExecutionContext = createContext()
        val mockHandle = mockk<EngineExecutionContext.ExecutionHandle>()
        context.setExecutionHandle(mockHandle)

        val copied = context.copy()

        assertSame(mockHandle, copied.executionHandle)
    }

    @Test
    fun `copy with activeSchema override`() {
        val context: EngineExecutionContext = createContext()
        val newSchema = ExecutionTestHelpers.createSchema(
            "type Query { baz: String }",
            resolvers = emptyMap()
        )

        val copied = context.copy(activeSchema = newSchema)

        assertSame(newSchema, copied.activeSchema)
        assertNotSame(context.activeSchema, copied.activeSchema)
    }

    @Test
    fun `copy with fieldScopeSupplier override`() {
        val context: EngineExecutionContext = createContext()
        val newFieldScope = EngineExecutionContextImpl.FieldExecutionScopeImpl(
            fragments = mapOf("TestFragment" to mockk(relaxed = true)),
            variables = mapOf("testVar" to "testValue")
        )
        val newSupplier = Supplier<EngineExecutionContext.FieldExecutionScope> { newFieldScope }

        val copied = context.copy(fieldScopeSupplier = newSupplier)

        assertSame(newFieldScope, copied.fieldScope)
    }

    @Test
    fun `copy with dataFetchingEnvironment override`() {
        val context: EngineExecutionContext = createContext()
        val mockDfe = mockk<DataFetchingEnvironment>(relaxed = true)

        val copied = context.copy(dataFetchingEnvironment = mockDfe)

        assertSame(mockDfe, copied.dataFetchingEnvironment)
        assertNull(context.dataFetchingEnvironment)
    }

    @Test
    fun `hasResolver returns false when no resolver registered`() {
        val context: EngineExecutionContext = createContext()

        val result = context.hasResolver("Query", "unregistered")

        assertEquals(false, result)
    }

    @Test
    fun `hasResolver returns true when resolver is registered`() {
        val mockResolver = mockk<FieldResolverDispatcher>(relaxed = true)
        val registry = DispatcherRegistry.Impl(
            fieldResolverDispatchers = mapOf(
                Coordinate("Query", "foo") to mockResolver
            ),
            nodeResolverDispatchers = emptyMap(),
            fieldCheckerDispatchers = emptyMap(),
            typeCheckerDispatchers = emptyMap()
        )
        val context: EngineExecutionContext = createContext(dispatcherRegistry = registry)

        assertTrue(context.hasResolver("Query", "foo"))
        assertEquals(false, context.hasResolver("Query", "bar"))
    }

    @Test
    fun `extension throws when used on non-impl class`() {
        val fakeContext = object : EngineExecutionContext {
            override val fullSchema: ViaductSchema get() = mockk()
            override val scopedSchema: ViaductSchema get() = mockk()
            override val activeSchema: ViaductSchema get() = mockk()
            override val engineSelectionSetFactory get() = mockk<EngineSelectionSet.Factory>()
            override val globalIDCodec: GlobalIDCodec get() = mockk<GlobalIDCodec>()
            override val requestContext: Any? get() = null
            override val engine get() = mockk<Engine>()
            override val executionHandle: EngineExecutionContext.ExecutionHandle? get() = null
            override val fieldScope get() = mockk<EngineExecutionContext.FieldExecutionScope>()

            override suspend fun resolveSelectionSet(
                resolverId: String,
                selectionSet: EngineSelectionSet,
                options: ResolveSelectionSetOptions
            ): EngineObjectData = mockk()

            override suspend fun completeSelectionSet(
                selectionSet: RequiredSelectionSet,
                arguments: Map<String, Any?>,
                options: CompleteSelectionSetOptions
            ): ExecutionResult = mockk()

            override suspend fun completeSelectionSet(
                selectionSet: RequiredSelectionSet,
                targetResult: ObjectEngineResult,
                arguments: Map<String, Any?>,
                options: CompleteSelectionSetOptions
            ): ExecutionResult = mockk()

            override fun createNodeReference(
                id: String,
                graphQLObjectType: GraphQLObjectType
            ) = mockk<NodeReference>()

            override fun hasModernNodeResolver(typeName: String) = false
        }

        val exception = assertFailsWith<IllegalStateException> {
            fakeContext.executeAccessChecksInModstrat
        }
        assertTrue(exception.message!!.contains("Expected EngineExecutionContextImpl"))
    }
}
