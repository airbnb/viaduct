package viaduct.engine.runtime.instrumentation

import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.schema.DataFetcher
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.ResolutionPolicy
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.EngineExecutionContextImpl
import viaduct.engine.runtime.EngineResultLocalContext
import viaduct.engine.runtime.MatSource
import viaduct.engine.runtime.ObjectEngineResultImpl
import viaduct.engine.runtime.context.CompositeLocalContext
import viaduct.engine.runtime.dfe.ViaductDataFetchingEnvironment

internal class ResolverDataFetcherInstrumentationTest {
    private val mockDispatcherRegistry: DispatcherRegistry = mockk()
    private val mockSchema: GraphQLSchema = mockk()
    private lateinit var testClass: ResolverDataFetcherInstrumentation
    private val typeName = "typeName"
    private val fieldName = "fieldName"

    @BeforeEach
    fun setupMocks() {
        clearMocks(mockDispatcherRegistry, mockSchema)
        testClass = ResolverDataFetcherInstrumentation(
            dispatcherRegistry = mockDispatcherRegistry,
        )
    }

    @Test
    fun `test getting provided dataFetcher whenever resolverRegistry is null`() {
        every { mockDispatcherRegistry.getFieldResolverDispatcher(any(), any()) } returns null
        val mockParams: InstrumentationFieldFetchParameters = mockk()
        val mockDataFetcher: DataFetcher<*> = mockk()
        mockDfEnv(mockParams)

        val receivedFetcher = testClass.instrumentDataFetcher(
            dataFetcher = mockDataFetcher,
            parameters = mockParams,
            state = null
        )
        assertEquals(mockDataFetcher, receivedFetcher)
    }

    @Test
    fun `test getting resolverData fetcher via intrumentation`() {
        val mockParams: InstrumentationFieldFetchParameters = mockk()
        val mockDataFetcher: DataFetcher<*> = mockk()
        mockDfEnv(mockParams)

        every { mockDispatcherRegistry.getFieldResolverDispatcher(typeName, fieldName) } returns mockk()
        every { mockDispatcherRegistry.getFieldCheckerDispatcher(typeName, fieldName) } returns mockk()

        val receivedFetcher = testClass.instrumentDataFetcher(
            dataFetcher = mockDataFetcher,
            parameters = mockParams,
            state = null
        )
        assertNotEquals(mockDataFetcher, receivedFetcher)
    }

    @Test
    fun `parent managed policy preserves data fetcher`() {
        val mockParams: InstrumentationFieldFetchParameters = mockk()
        val mockDataFetcher: DataFetcher<*> = mockk()
        mockDfEnv(
            mockParams,
            EngineExecutionContextImpl.FieldExecutionScopeImpl(
                resolutionPolicy = ResolutionPolicy.PARENT_MANAGED,
            ),
        )
        every { mockDispatcherRegistry.getFieldResolverDispatcher(typeName, fieldName) } returns mockk()

        val receivedFetcher = testClass.instrumentDataFetcher(
            dataFetcher = mockDataFetcher,
            parameters = mockParams,
            state = null,
        )

        assertEquals(mockDataFetcher, receivedFetcher)
    }

    @Test
    fun `a field its parent's value supplies preserves data fetcher`() {
        val mockDataFetcher: DataFetcher<*> = mockk()

        assertEquals(mockDataFetcher, instrumentUnderSuppliedParent(mockDataFetcher, supplied = true))
    }

    @Test
    fun `a field its parent's value does not supply uses its resolver`() {
        val mockDataFetcher: DataFetcher<*> = mockk()

        assertNotEquals(mockDataFetcher, instrumentUnderSuppliedParent(mockDataFetcher, supplied = false))
    }

    private fun instrumentUnderSuppliedParent(
        dataFetcher: DataFetcher<*>,
        supplied: Boolean,
    ): DataFetcher<*> {
        val parentType = GraphQLObjectType.newObject().name(typeName).build()
        val parent = ObjectEngineResultImpl.newForType(
            parentType,
            MatSource.Ledger(mockk(), suppliedFields = { type, name -> supplied && type === parentType && name == fieldName }),
        )
        val mockParams: InstrumentationFieldFetchParameters = mockk()
        mockDfEnv(mockParams)
        val dfEnv = mockParams.environment as ViaductDataFetchingEnvironment
        every { dfEnv.parentType } returns parentType
        every { dfEnv.getLocalContext<CompositeLocalContext?>() } returns
            CompositeLocalContext.withContexts(
                EngineResultLocalContext(
                    rootEngineResult = parent,
                    currentObjectEngineResult = parent,
                    queryEngineResult = parent,
                    executionStrategyParams = null,
                    executionContext = null,
                )
            )
        every { mockDispatcherRegistry.getFieldResolverDispatcher(typeName, fieldName) } returns mockk()

        return testClass.instrumentDataFetcher(dataFetcher = dataFetcher, parameters = mockParams, state = null)
    }

    @Test
    fun `test hasResolver`() {
        every { mockDispatcherRegistry.getFieldResolverDispatcher(typeName, fieldName) } returns mockk()
        assertTrue(testClass.hasResolver(typeName, fieldName))
    }

    private fun mockDfEnv(
        mockParams: InstrumentationFieldFetchParameters,
        fieldScope: EngineExecutionContext.FieldExecutionScope =
            EngineExecutionContextImpl.FieldExecutionScopeImpl(),
    ) {
        val dfEnv: ViaductDataFetchingEnvironment = mockk()
        every { mockParams.environment } returns dfEnv

        val eec: EngineExecutionContext = mockk()
        every { eec.fieldScope } returns fieldScope
        every { dfEnv.engineExecutionContext } returns eec

        val parentType: GraphQLScalarType = mockk()
        every { dfEnv.parentType } returns parentType
        every { parentType.name } returns typeName

        val fieldDef: GraphQLFieldDefinition = mockk()
        every { dfEnv.fieldDefinition } returns fieldDef
        every { fieldDef.name } returns fieldName
    }
}
