package viaduct.engine.runtime.instrumentation

import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.schema.DataFetcher
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.ViaductDataFetchingEnvironment
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.EngineExecutionContextImpl

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
    fun `test hasResolver`() {
        every { mockDispatcherRegistry.getFieldResolverDispatcher(typeName, fieldName) } returns mockk()
        assertTrue(testClass.hasResolver(typeName, fieldName))
    }

    private fun mockDfEnv(mockParams: InstrumentationFieldFetchParameters) {
        val dfEnv: ViaductDataFetchingEnvironment = mockk()
        every { mockParams.environment } returns dfEnv

        val eec: EngineExecutionContext = mockk()
        every { eec.fieldScope } returns EngineExecutionContextImpl.FieldExecutionScopeImpl()
        every { dfEnv.engineExecutionContext } returns eec

        val parentType: GraphQLScalarType = mockk()
        every { dfEnv.parentType } returns parentType
        every { parentType.name } returns typeName

        val fieldDef: GraphQLFieldDefinition = mockk()
        every { dfEnv.fieldDefinition } returns fieldDef
        every { fieldDef.name } returns fieldName
    }
}
