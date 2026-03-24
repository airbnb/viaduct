@file:Suppress("ForbiddenImport")

package viaduct.tenant.runtime.featuretests.fixtures

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import viaduct.service.api.ExecutionInput
import viaduct.service.api.ExecutionResult
import viaduct.service.runtime.StandardViaduct

/**
 * Test harness for the Viaduct Modern engine configured with in-memory resolvers.
 * Allows arbitrary schemas and resolvers to be tested using a Modern-only engine free from the legacy engine.
 * Registered resolvers, resolver bases, and GRTs do not have to have any particular package and can be declared inline,
 * e.g. see `ViaductModernEndToEndTests`
 *
 * Usage:
 *
 * ```kotlin
 *    FeatureTestBuilder(<schema>)
 *      // configure a resolver class for a schema field
 *      .resolver(Query::class, FooField::class, FooFieldResolver::class)
 *      // or configure a resolver function that uses GRTs
 *      .resolver("Query" to "bar") { Bar.newBuilder(it).value(2).build() }
 *      // or configure a simple resolver function that does not read or write GRTs
 *      .resolver("Query" to "baz") { mapOf("value" to 2) }
 *      .build()
 *      .assertJson("<expected-json-string>", "<query>")
 * ```
 */
@OptIn(ExperimentalCoroutinesApi::class)
open class FeatureTest(
    val standardViaduct: StandardViaduct,
) {
    private suspend fun executeAsync(
        query: String,
        variables: Map<String, Any?> = mapOf(),
        operationName: String? = null,
    ): CompletableFuture<ExecutionResult> {
        val executionInput = ExecutionInput.create(
            operationText = query,
            variables = variables,
            operationName = operationName
        )
        return standardViaduct.executeAsync(executionInput)
    }

    open fun execute(
        query: String,
        variables: Map<String, Any?> = mapOf(),
        operationName: String? = null,
    ): ExecutionResult {
        lateinit var result: ExecutionResult
        runBlocking {
            result = executeAsync(query, variables, operationName).await()
        }
        return result
    }

    fun assertJson(
        expectedJson: String,
        query: String,
        variables: Map<String, Any?> = mapOf()
    ): Unit = execute(query, variables).assertJson(expectedJson)
}

/**
 * Assert that this result serializes to same value as [expectedJson].
 *
 * @param expectedJson a JSON string. The string may use some short-hand conventions,
 *  including unquoted object keys, trailing commas, and comments
 */
fun ExecutionResult.assertJson(expectedJson: String) {
    val expected = try {
        mapper.readValue<Map<String, Any?>>(expectedJson)
    } catch (e: Exception) {
        throw IllegalArgumentException("Cannot parse expectedJson", e)
    }
    assertEquals(expected, toSpecification())
}

// configure an ObjectMapper that allows parsing compact JSON
private val mapper: ObjectMapper = ObjectMapper()
    .configure(JsonParser.Feature.ALLOW_COMMENTS, true)
    .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
    .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
    .configure(JsonParser.Feature.ALLOW_TRAILING_COMMA, true)
