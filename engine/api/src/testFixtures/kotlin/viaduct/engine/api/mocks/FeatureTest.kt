@file:Suppress("ForbiddenImport")

package viaduct.engine.api.mocks

import graphql.ExecutionResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.test.assertJson as realAssertJson
import viaduct.service.api.mocks.MockTenantAPIBootstrapperBuilder
import viaduct.service.api.spi.mocks.MockFlagManager
import viaduct.service.runtime.SchemaConfiguration
import viaduct.service.runtime.StandardViaduct

/**
 * Convert a MockTenantModuleBootstrapper into a Viaduct builder
 * that has been initialized as follows:
 *
 * * withTenantAPIBootstrapperBuilders: constructed from `this`
 * * withSchemaRegistryBuilder: constructed `this.schema` and full-schema registered as "" (blank string)
 * * withFlagManager: MockFlagManager.Enabled
 * * withCheckerExecutorFactory: constructed from `this`
 *
 * Note this function assumes that `this` has a non-null
 * `MockTenantModuleBootstrapper.schema` property and will fail if it
 * doesn't.
 */
fun MockTenantModuleBootstrapper.toViaductBuilder(): StandardViaduct.Builder {
    val mods = listOf(this)

    val tenantAPIBootstrapperBuilder = MockTenantAPIBootstrapperBuilder(MockTenantAPIBootstrapper(mods))

    val checkerExecutorFactory = MockCheckerExecutorFactory(
        checkerExecutors = checkerExecutors,
        typeCheckerExecutors = typeCheckerExecutors
    )

    val schemaConfiguration = SchemaConfiguration.fromSchema(schema) // this schema should already be built with the actual wiring

    return StandardViaduct.Builder()
        .withTenantAPIBootstrapperBuilders(listOf(tenantAPIBootstrapperBuilder))
        .withFlagManager(MockFlagManager.Enabled)
        .withSchemaConfiguration(schemaConfiguration)
        .withCheckerExecutorFactory(checkerExecutorFactory)
}

/**
 * Test harness for the Viaduct engine configured with in-memory resolvers.
 * Allows arbitrary schemas and resolvers to be tested using the engine runtime
 * with lower-level EngineExecutionContext-based resolvers.
 *
 * This is the engine-level equivalent of the tenant runtime FeatureTest.
 *
 * Usage:
 * ```kotlin
 *    MockTenantModuleBootstrapper("""
 *       type Query {
 *           foo: String
 *           bar(answer: Int): Int
 *       }"""
 *    ) {
 *        fieldWithValue("Query" to "foo", "hello world") // Resolve to a constant
 *        field("Query" to "bar") { // Resolve using a function
 *            fn { args, objectValue, selections, context ->
 *                args["answer"]
 *            }
 *        }
 *    }.runFeatureTest {
 *        runQuery("{ foo bar(42) }")
 *           .assertJson("""{"data": {"foo": "hello world", "bar": 42}}""")
 *    }
 *
 * ```
 *
 * See [MockTenantModuleBootstrapper.viaductBuilder] to understand how the
 * Viaduct engine is initialized for the feature test.
 *
 * Inside the FeatureTest block are the following:
 *
 * * this: a StandardViaduct (whose functions can be called unqualified)
 * * ExecutionResult.assertJson(String): compares `this` converted to JSON to an expectation
 */
fun MockTenantModuleBootstrapper.runFeatureTest(block: FeatureTest.() -> Unit) {
    val viaduct: StandardViaduct = toViaductBuilder().build()
    viaduct.runFeatureTest(block)
}

fun StandardViaduct.runFeatureTest(block: FeatureTest.() -> Unit) = FeatureTest(this).block()

@OptIn(ExperimentalCoroutinesApi::class)
class FeatureTest(
    val viaduct: StandardViaduct,
) {
    /**
     * Assert that this result serializes to same value as [expectedJson].
     *
     * @param expectedJson a JSON string. The string may use some short-hand conventions,
     *  including unquoted object keys, trailing commas, and comments
     */
    fun ExecutionResult.assertJson(expectedJson: String): Unit = this.realAssertJson(expectedJson)
}

suspend inline fun <reified T : Any?> EngineObjectData.fetchAs(selection: String) = this.fetch(selection) as T

suspend inline fun <reified T : Any?> Map<String, Any?>.getAs(key: String) = this[key] as T
