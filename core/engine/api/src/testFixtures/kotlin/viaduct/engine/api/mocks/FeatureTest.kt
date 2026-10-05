@file:Suppress("DEPRECATION", "ForbiddenImport")
@file:OptIn(viaduct.apiannotations.InternalApi::class, viaduct.apiannotations.VisibleForTest::class)

package viaduct.engine.api.mocks

import com.google.inject.ProvisionException
import graphql.ExecutionResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.Engine
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.GraphQLBuildError
import viaduct.graphql.test.assertJson as realAssertJson
import viaduct.service.api.ExecutionInput
import viaduct.service.api.SchemaId
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.mocks.MockFlagManager
import viaduct.service.runtime.SchemaConfiguration
import viaduct.service.runtime.StandardViaduct

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
 * Delegates to [EngineTestModule.runFeatureTest], which shows how the Viaduct engine is
 * initialized for the feature test.
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
    schema: EngineSchema? = null,
    engineConfig: EngineConfiguration? = null,
    block: FeatureTest.() -> Unit
) = toEngineTestModule().runFeatureTest(withoutDefaultQueryNodeResolvers, schema, engineConfig, block)

/**
 * Run a feature test through the module-config bootstrap path using in-memory executors.
 */
fun EngineTestModule.runFeatureTest(
    withoutDefaultQueryNodeResolvers: Boolean = false,
    schema: EngineSchema? = null,
    engineConfig: EngineConfiguration? = null,
    block: FeatureTest.() -> Unit,
) = runStandardViaductFeatureTest(
    engine2Enabled = false,
    withoutDefaultQueryNodeResolvers = withoutDefaultQueryNodeResolvers,
    schema = schema,
    engineConfig = engineConfig,
    block = block,
)

/**
 * Runs a feature test through the production [StandardViaduct] boundary.
 *
 * Both engine feature suites use this construction path. [engine2Enabled] is the only engine
 * selection input; schema registration, module bootstrap, dispatcher construction, and request
 * execution are otherwise identical.
 */
fun EngineTestModule.runStandardViaductFeatureTest(
    engine2Enabled: Boolean,
    withoutDefaultQueryNodeResolvers: Boolean = false,
    schema: EngineSchema? = null,
    engineConfig: EngineConfiguration? = null,
    block: FeatureTest.() -> Unit,
) {
    val config = engineConfig ?: EngineConfiguration.featureTestDefault
    val schemaId = SchemaId.Scoped("engine-feature-test", setOf("engine-feature-test"))
    val schemaConfiguration = SchemaConfiguration.fromSchema(fullSchema, scopes = emptySet())
    schemaConfiguration.registerSchema(schemaId, { schema ?: fullSchema })
    val builder =
        StandardViaduct.Builder()
            .withTenantModuleInjectorFactory(MockExecutorCodeInjector(mockExecutorRegistry))
            .withExecutorRegistryConfigSources(listOf(toModuleConfigSource()))
            .withCheckerExecutorFactory(
                MockCheckerExecutorFactory(
                    checkerExecutors = checkerExecutors,
                    typeCheckerExecutors = typeCheckerExecutors,
                ),
            ).withSchemaConfiguration(schemaConfiguration)
            .withLenientResolverValidation()
            .allowSubscriptions(true)
            .withFlagManager(
                object : FlagManager {
                    override fun isEnabled(flag: FlagManager.Flag): Boolean =
                        when (flag) {
                            FlagManager.Flags.ENGINE2_ENABLED -> engine2Enabled
                            FlagManager.Flags.ENGINE2_BATCHING -> false
                            else -> config.flagManager.isEnabled(flag)
                        }
                },
            ).withDataFetcherExceptionHandler(config.dataFetcherExceptionHandler)
            .withResolverErrorReporter(config.resolverErrorReporter)
            .withDataFetcherErrorBuilder(config.resolverErrorBuilder)
            .withInstrumentation(config.additionalInstrumentation, config.chainInstrumentationWithDefaults)
            .withCoroutineInterop(config.coroutineInterop)
            .withResolverInstrumentation(config.resolverInstrumentation)
            .withFieldSelectivityProvider(config.fieldSelectivityProvider)
            .withMaterializedFieldValueReader(config.materializedFieldValueReader)
            .withGlobalIDCodec(config.globalIDCodec)

    config.meterRegistry?.let(builder::withMeterRegistry)
    if (config.airbnbBypassPolicyCheckDuringCompletion) {
        builder.enableAirbnbBypassDoNotUse(config.tenantNameResolver)
    }
    if (withoutDefaultQueryNodeResolvers) {
        builder.withoutDefaultQueryNodeResolvers()
    }

    val viaduct =
        try {
            builder.build()
        } catch (error: GraphQLBuildError) {
            throw unwrapBuildFailure(error)
        } catch (error: ProvisionException) {
            throw unwrapBuildFailure(error)
        }
    FeatureTest(viaduct, schemaId).block()
}

val EngineConfiguration.Companion.featureTestDefault: EngineConfiguration
    get() = EngineConfiguration.default.copy(
        flagManager = MockFlagManager.RuntimeFlagsEnabled,
        chainInstrumentationWithDefaults = true,
    )

class FeatureTest(
    private val viaduct: StandardViaduct,
    private val schemaId: SchemaId,
) {
    /** The selected engine, retained for focused legacy-engine test helpers. */
    val engine: Engine
        get() = viaduct.engineRegistry.getEngine(schemaId)

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
    ): ExecutionResult = execute(query, variables)

    fun runQueryWithin(
        query: String,
        variables: Map<String, Any?> = emptyMap(),
        timeoutMillis: Long = 1_000,
    ): ExecutionResult {
        require(timeoutMillis > 0) { "Timeout must be positive" }
        return execute(query, variables, timeoutMillis)
    }

    /**
     * Assert that this result serializes to same value as [expectedJson].
     *
     * @param expectedJson a JSON string. The string may use some short-hand conventions,
     *  including unquoted object keys, trailing commas, and comments
     */
    fun ExecutionResult.assertJson(expectedJson: String): Unit = this.realAssertJson(expectedJson)

    private fun execute(
        query: String,
        variables: Map<String, Any?>,
        timeoutMillis: Long? = null,
    ): ExecutionResult {
        val input =
            ExecutionInput.create(
                operationText = query,
                variables = variables,
                requestContext = Any(),
            )
        val result =
            runBlocking {
                if (timeoutMillis == null) {
                    viaduct.execute(input, schemaId)
                } else {
                    withTimeout(timeoutMillis) { viaduct.execute(input, schemaId) }
                }
            }
        @Suppress("UNCHECKED_CAST")
        return ExecutionResult.fromSpecification(result.toSpecification() as Map<String, Any>)
    }
}

private fun unwrapBuildFailure(error: Throwable): Throwable =
    when (error) {
        is GraphQLBuildError, is ProvisionException -> error.cause?.let(::unwrapBuildFailure) ?: error
        else -> error
    }

suspend inline fun <reified T : Any?> EngineObjectData.fetchAs(selection: String) = this.fetch(selection) as T

inline fun <reified T : Any?> EngineObjectData.Sync.getAs(selection: String) = this.get(selection) as T

inline fun <reified T : Any?> Map<String, Any?>.getAs(key: String) = this[key] as T
