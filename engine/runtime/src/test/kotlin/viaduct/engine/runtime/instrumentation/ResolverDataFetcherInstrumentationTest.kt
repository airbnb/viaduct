package viaduct.engine.runtime.instrumentation

import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
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
import viaduct.engine.api.FieldCheckerDispatcherRegistry
import viaduct.engine.api.FieldResolverDispatcherRegistry

internal class ResolverDataFetcherInstrumentationTest {
    private val mockDispathcerRegistry: FieldResolverDispatcherRegistry = mockk()
    private val mockCheckerRegistry: FieldCheckerDispatcherRegistry = mockk()
    private val mockSchema: GraphQLSchema = mockk()
    private lateinit var testClass: ResolverDataFetcherInstrumentation
    private val typeName = "typeName"
    private val fieldName = "fieldName"

    @BeforeEach
    fun setupMocks() {
        clearMocks(mockDispathcerRegistry, mockCheckerRegistry, mockSchema)
        testClass = ResolverDataFetcherInstrumentation(
            dispatcherRegistry = mockDispathcerRegistry,
            checkerRegistry = mockCheckerRegistry,
        )
    }

    @Test
    fun `test getting provided dataFetcher whenever resolverRegistry is null`() {
        every { mockDispathcerRegistry.getFieldResolverDispatcher(any(), any()) } returns null
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

        every { mockDispathcerRegistry.getFieldResolverDispatcher(typeName, fieldName) } returns mockk()
        every { mockCheckerRegistry.getFieldCheckerDispatcher(typeName, fieldName) } returns mockk()

        val receivedFetcher = testClass.instrumentDataFetcher(
            dataFetcher = mockDataFetcher,
            parameters = mockParams,
            state = null
        )
        assertNotEquals(mockDataFetcher, receivedFetcher)
    }

    @Test
    fun `test hasResolver`() {
        every { mockDispathcerRegistry.getFieldResolverDispatcher(typeName, fieldName) } returns mockk()
        assertTrue(testClass.hasResolver(typeName, fieldName))
    }

    private fun mockDfEnv(mockParams: InstrumentationFieldFetchParameters) {
        val dfEnv: DataFetchingEnvironment = mockk()
        every { mockParams.environment } returns dfEnv

        val parentType: GraphQLScalarType = mockk()
        every { dfEnv.parentType } returns parentType
        every { parentType.name } returns typeName

        val fieldDef: GraphQLFieldDefinition = mockk()
        every { dfEnv.fieldDefinition } returns fieldDef
        every { fieldDef.name } returns fieldName
    }
}
