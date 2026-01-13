@file:Suppress("ForbiddenImport")

package viaduct.engine.api.mocks

import graphql.ExecutionResult
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import viaduct.engine.EngineConfiguration
import viaduct.engine.EngineFactory
import viaduct.engine.api.Engine
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ExecutionInput
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.flatten
import viaduct.engine.runtime.execution.DefaultCoroutineInterop
import viaduct.engine.runtime.tenantloading.DispatcherRegistryFactory
import viaduct.engine.runtime.tenantloading.ExecutorValidator
import viaduct.graphql.test.assertJson as realAssertJson
import viaduct.service.api.mocks.MockTenantAPIBootstrapperBuilder
import viaduct.service.api.spi.mocks.MockFlagManager
import viaduct.service.runtime.noderesolvers.ViaductNodeResolverAPIBootstrapper

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
 * See [MockTenantModuleBootstrapper.toEngineFactory] to understand how the
 * Viaduct engine is initialized for the feature test.
 *
 * Inside the FeatureTest block are the following:
 *
 * * this: an [Engine] (whose functions can be called unqualified)
 * * ExecutionResult.assertJson(String): compares `this` converted to JSON to an expectation
 *
 * Note that Viaduct turns exceptions thrown by resolvers into field errors.  Thus,
 * assertions placed in resolvers will _not_ cause tests to fail if you don't check
 * query results to esnure that they have no errors.
 *
 * @param engineConfig The [EngineConfigruation] to use for this test. If null, EngineConfiguration.featureTestDefault will be used
 */
fun MockTenantModuleBootstrapper.runFeatureTest(
    withoutDefaultQueryNodeResolvers: Boolean = false,
    schema: ViaductSchema? = null,
    engineConfig: EngineConfiguration? = null,
    block: FeatureTest.() -> Unit
) {
    val executableSchema = schema ?: fullSchema
    val engine = toEngineFactory(withoutDefaultQueryNodeResolvers, engineConfig).create(executableSchema, fullSchema = fullSchema)
    FeatureTest(engine).block()
}

val EngineConfiguration.Companion.featureTestDefault: EngineConfiguration
    get() = EngineConfiguration.default.copy(
        flagManager = MockFlagManager.Enabled,
        chainInstrumentationWithDefaults = true,
    )

/**
 * Convert a MockTenantModuleBootstrapper into an EngineFactory
 * that has been initialized with a dispatcher registry constructed from:
 *
 * - the full schema
 * - mock tenant API bootstrappers
 * - a mock checker executor factory
 *
 * and an [EngineConfiguration] constructed with MockFlagManager.Enabled.
 */
private fun MockTenantModuleBootstrapper.toEngineFactory(
    withoutDefaultQueryNodeResolvers: Boolean,
    engineConfig: EngineConfiguration?
): EngineFactory {
    val mods = listOf(this)
    val tenantAPIBootstrapper = buildList {
        add(MockTenantAPIBootstrapperBuilder(MockTenantAPIBootstrapper(mods)))
        if (!withoutDefaultQueryNodeResolvers) {
            add(ViaductNodeResolverAPIBootstrapper.Builder())
        }
    }.map { it.create() }.flatten()

    val checkerExecutorFactory = MockCheckerExecutorFactory(
        checkerExecutors = checkerExecutors,
        typeCheckerExecutors = typeCheckerExecutors
    )
    val validator = ExecutorValidator(fullSchema)
    val dispatcherRegistry = DispatcherRegistryFactory(
        tenantAPIBootstrapper,
        validator,
        checkerExecutorFactory
    ).create(fullSchema)
    val config = engineConfig ?: EngineConfiguration.featureTestDefault
    return EngineFactory(config, dispatcherRegistry)
}

class FeatureTest(
    val engine: Engine
) {
    /**
     * Runs a query on the underlying engine with the given query and optional variables.
     *
     * @param query a GraphQL query string to execute
     * @param variables a map of variable values
     * @return the execution result from execution the engine
     */
    fun runQuery(
        query: String,
        variables: Map<String, Any?> = emptyMap(),
    ): ExecutionResult {
        val input = ExecutionInput(
            operationText = query,
            variables = variables,
            requestContext = Any(),
        )
        return runBlocking {
            DefaultCoroutineInterop.enterThreadLocalCoroutineContext(coroutineContext) {
                engine.execute(input)
            }.await()
        }
    }

    /**
     * Assert that this result serializes to same value as [expectedJson].
     *
     * @param expectedJson a JSON string. The string may use some short-hand conventions,
     *  including unquoted object keys, trailing commas, and comments
     */
    fun ExecutionResult.assertJson(expectedJson: String): Unit = this.realAssertJson(expectedJson)
}

suspend inline fun <reified T : Any?> EngineObjectData.fetchAs(selection: String) = this.fetch(selection) as T

inline fun <reified T : Any?> Map<String, Any?>.getAs(key: String) = this[key] as T
