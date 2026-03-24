package viaduct.engine.runtime.execution

import graphql.schema.DataFetcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class JSONScalarConformanceTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun check(jsonValue: Any?) {
        Conformer(
            """
                scalar JSON
                type Query { x:JSON }
            """.trimIndent(),
            resolvers = mapOf("Query" to mapOf("x" to DataFetcher { jsonValue }))
        ) {
            check("{ x }") { _, (act) ->
                Assertions.assertEquals(mapOf("x" to jsonValue), act.getData())
            }
        }
    }

    @Test
    fun `null value`() = check(null)

    @Test
    fun `scalar value`() = check(1)

    @Test
    fun `map value`() = check(mapOf("a" to 1))

    @Test
    fun `nested map value`() = check(mapOf("a" to mapOf("b" to 1)))

    @Test
    fun `list value`() = check(listOf(1, 2, null))

    @Test
    fun `heterogenous list value`() = check(listOf(1, mapOf("a" to 2)))

    @Test
    fun `nested list value`() = check(listOf(listOf(1, 2), null, listOf(3, null)))

    @Test
    fun `list of maps value`() = check(listOf(mapOf("a" to 1), mapOf("a" to 2)))
}
