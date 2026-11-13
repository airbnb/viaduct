package viaduct.engine.runtime.execution

import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.graphql.test.assertJson

interface OperationDirectivesContract : QueryExecutionContract {
    private fun module(resolverCalls: AtomicInteger = AtomicInteger()) =
        EngineTestModule(
            """
        directive @metadata(tag: String!) on QUERY | MUTATION | SUBSCRIPTION
        directive @fieldOnly on FIELD
        extend type Query { value: Int }
        extend type Mutation { value: Int }
        extend type Subscription { value: Int }
            """.trimIndent(),
        ) {
            for (typeName in listOf("Query", "Mutation", "Subscription")) {
                field(typeName to "value") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            resolverCalls.incrementAndGet()
                            9
                        }
                    }
                }
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["query", "mutation", "subscription"])
    fun `validated operation directives permit field execution`(operation: String) {
        module().runContractTest {
            withTimeout(
                "$operation(\$tag: String!) @metadata(tag: \$tag) { value }",
                variables = mapOf("tag" to "request"),
                timeoutMillis = 5_000,
            ).assertJson("""{data: {value: 9}}""")
        }
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "@metadata | Missing directive argument | tag",
            "@unknown | Unknown directive | unknown",
            "@fieldOnly | not allowed here | fieldOnly",
        ],
    )
    fun `invalid operation directives fail validation before resolver execution`(
        directive: String,
        errorReason: String,
        errorDetail: String,
    ) {
        val resolverCalls = AtomicInteger()
        module(resolverCalls).runContractTest {
            val result = withTimeout("query $directive { value }", timeoutMillis = 5_000)

            assertEquals(1, result.errors.size)
            val error = result.errors.single()
            assertEquals("ValidationError", error.errorType.toString())
            assertTrue(error.message.contains(errorReason), error.message)
            assertTrue(error.message.contains(errorDetail), error.message)
            assertFalse(result.isDataPresent)
            assertEquals(0, resolverCalls.get())
        }
    }
}
