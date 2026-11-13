package viaduct.engine.runtime.execution

import graphql.execution.instrumentation.Instrumentation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.createSchemaWithWiring
import viaduct.graphql.test.assertJson

interface ExecutionInstrumentationContract : QueryExecutionContract {
    val executionInstrumentation: Instrumentation
    val mixedIntrospectionError: String? get() = null

    @Test
    fun `configured instrumentation supports root and field execution`() {
        EngineTestModule("extend type Query { value: Int }") {
            fieldWithValue("Query" to "value", 9)
        }.runContractTest(
            engineConfig = EngineConfiguration(
                additionalInstrumentation = executionInstrumentation,
                chainInstrumentationWithDefaults = true,
            ),
        ) {
            withTimeout("{ __typename value }", timeoutMillis = 5_000)
                .assertJson("""{data: {__typename: "Query", value: 9}}""")
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "{ inspection: __schema { queryType { fields { name } } } }",
            "{ inspection: __schema { queryType { fields { name } } } visible }",
            "{ inspection: __type(name: \"Query\") { fields { name } } }",
            "{ inspection: __type(name: \"Query\") { fields { name } } visible }",
            "{ ...Root visible } fragment Root on Query { inspection: __type(name: \"Query\") { fields { name } } }",
        ],
    )
    fun `configured instrumentation preserves scoped pure and mixed introspection`(query: String) {
        val scopedSchema = createSchemaWithWiring("extend type Query { visible: Int }")
        EngineTestModule("extend type Query { visible: Int hidden: Int }") {
            fieldWithValue("Query" to "visible", 1)
            fieldWithValue("Query" to "hidden", 2)
        }.runContractTest(
            schema = scopedSchema,
            engineConfig = EngineConfiguration(
                additionalInstrumentation = executionInstrumentation,
                chainInstrumentationWithDefaults = true,
            ),
        ) {
            val result = withTimeout(query, timeoutMillis = 5_000)
            val expectedError = mixedIntrospectionError.takeIf { "visible" in query }
            if (expectedError != null) {
                assertEquals(listOf(expectedError), result.errors.map { it.message })
                assertFalse(result.isDataPresent)
                return@runContractTest
            }
            assertEquals(emptyList<Any>(), result.errors)
            val data = result.getData<Map<String, Any?>>()
            val inspection = data.getValue("inspection") as Map<*, *>
            val inspectedType = if ("__schema" in query) inspection["queryType"] as Map<*, *> else inspection
            val fields = (inspectedType["fields"] as List<*>).map { (it as Map<*, *>)["name"] }
            assertTrue("visible" in fields)
            assertFalse("hidden" in fields)
            if ("visible" in query) assertEquals(1, data.getValue("visible"))
        }
    }
}
