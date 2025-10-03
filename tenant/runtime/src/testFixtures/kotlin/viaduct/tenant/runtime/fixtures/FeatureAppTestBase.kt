@file:Suppress("ForbiddenImport")

package viaduct.tenant.runtime.fixtures

import com.google.inject.Guice
import com.google.inject.Injector
import graphql.ExecutionResult
import graphql.schema.GraphQLScalarType
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import viaduct.api.bootstrap.ViaductTenantAPIBootstrapper
import viaduct.api.reflect.Type
import viaduct.api.types.NodeObject
import viaduct.service.ViaductBuilder
import viaduct.service.api.ExecutionInput
import viaduct.service.api.spi.mocks.MockFlagManager
import viaduct.service.runtime.SchemaRegistryConfiguration
import viaduct.service.runtime.StandardViaduct
import viaduct.tenant.runtime.bootstrap.GuiceTenantCodeInjector
import viaduct.tenant.runtime.bootstrap.ViaductTenantResolverClassFinderFactory
import viaduct.tenant.runtime.globalid.GlobalIDCodecImpl
import viaduct.tenant.runtime.globalid.GlobalIDImpl
import viaduct.tenant.runtime.internal.ReflectionLoaderImpl

/**
 * Base class for testing GraphQL feature applications with Viaduct.
 *
 * Usage:
 * 1. Extend this class in your test
 * 2. Override the `sdl` property with your GraphQL schema (between #START_SCHEMA and #END_SCHEMA markers)
 * 3. Override the `customScalar` property with the set of custom scalars defined in the schema
 * 4. Define your resolver implementations as inner classes annotated with @Resolver
 * 5. Use the `execute()` method to run queries against your implementation
 *
 * Example:
 * ```kotlin
 * class MyFeatureAppTest : FeatureAppTestBase() {
 *     override var sdl = """
 *         |#START_SCHEMA <- schema start marker needed
 *         | type Query {
 *         |    hello: String @resolver
 *         | }
 *         |#END_SCHEMA <- schema end marker needed
 *     """.trimMargin()
 *
 *     @Resolver
 *     class Query_HelloResolver : QueryResolvers.Hello() {
 *         override suspend fun resolve(ctx: Context) = "Hello, World!"
 *     }
 *
 *     @Test
 *     fun testHelloQuery() {
 *         val result = execute("test", "{ hello }")
 *         // Assert on result
 *     }
 * }
 *
 * **Important**: GRTs are created per package namespace. Multiple tests in the same package will
 * share the same generated classes, which can cause conflicts. To avoid this, place each feature
 * test app in its own separate package.
 *```
 */
abstract class FeatureAppTestBase {
    open lateinit var sdl: String
        protected set
    open val defaultScopeId = "public"
    open val defaultSchemaId = "public"

    protected open var customScalars: Set<GraphQLScalarType> = emptySet()
    private val injector: Injector by lazy { Guice.createInjector() }
    private val guiceTenantCodeInjector by lazy { GuiceTenantCodeInjector(injector) }
    private val flagManager = MockFlagManager()

    // GlobalID codec for creating GlobalID strings in tests
    private val globalIdCodec by lazy {
        val reflectionLoader = ReflectionLoaderImpl { name: String ->
            Class.forName("$derivedClassPackagePrefix.$name").kotlin
        }
        GlobalIDCodecImpl(reflectionLoader)
    }

    // package name of the derived class
    private val derivedClassPackagePrefix: String =
        this::class.java.`package`?.name ?: throw RuntimeException(
            "Unable to read package name from subclass ${this::class.simpleName}"
        )

    // resolver class finder factory for feature test app use case
    private val tenantResolverClassFinderFactory = ViaductTenantResolverClassFinderFactory(
        grtPackagePrefix = derivedClassPackagePrefix
    )

    protected val viaductTenantAPIBootstrapperBuilder =
        ViaductTenantAPIBootstrapper.Builder()
            .tenantCodeInjector(guiceTenantCodeInjector)
            .tenantResolverClassFinderFactory(tenantResolverClassFinderFactory)
            .tenantPackagePrefix(derivedClassPackagePrefix)

    private lateinit var viaductBuilder: ViaductBuilder
    lateinit var viaductSchemaRegistryConfiguration: SchemaRegistryConfiguration
    lateinit var viaductService: StandardViaduct

    fun withViaductBuilder(builderUpdate: ViaductBuilder.() -> Unit) {
        viaductBuilder.apply(builderUpdate)
    }

    fun withSchemaRegistryConfiguration(config: SchemaRegistryConfiguration) {
        viaductBuilder = viaductBuilder.withSchemaRegistryConfiguration(config)
        viaductSchemaRegistryConfiguration = config
    }

    @BeforeEach
    open fun initViaductBuilder() {
        if (!::viaductBuilder.isInitialized) {
            viaductBuilder = ViaductBuilder()
                .withFlagManager(flagManager)
                .withTenantAPIBootstrapperBuilder(viaductTenantAPIBootstrapperBuilder)
        }
    }

    /**
     * Creates a GlobalID string for the given type and internal ID.
     * This is a helper method to avoid repeating ctx.globalIDStringFor() calls in tests.
     * This method can be accessed from resolver classes to generate GlobalIDs outside of Viaduct context.
     *
     * @param type The type reflection object (e.g., Foo.Reflection)
     * @param internalId The internal ID to create a GlobalID for
     * @return A GlobalID string
     */
    fun <T : NodeObject> createGlobalIdString(
        type: Type<T>,
        internalId: String
    ): String {
        val globalId = GlobalIDImpl(type, internalId)
        return globalIdCodec.serialize(globalId)
    }

    /**
     * Helper function to get internalId from the GlobalID given a generated Type.
     */
    fun <T : NodeObject> getInternalId(globalID: String): String {
        return globalIdCodec.deserialize<T>(globalID).internalID
    }

    /**
     * Executes a query against the test application.
     *
     * @param scopeId The scope ID to use for the query.
     * @param query The query to execute.
     * @param variables The variables to use for the query.
     *
     * @return The result of the query execution.
     */
    open fun execute(
        query: String,
        variables: Map<String, Any?> = mapOf(),
        schemaId: String = defaultSchemaId
    ): ExecutionResult {
        return runBlocking {
            tryBuildViaductService()
            val executionInput = ExecutionInput.create(
                schemaId = schemaId,
                operationText = query,
                variables = variables,
            )
            val result = viaductService.executeAsync(executionInput).await()
            result
        }
    }

    /**
     * Attempts to build the [StandardViaduct] instance if it has not been initialized yet.
     */
    @Suppress("TooGenericExceptionCaught")
    fun tryBuildViaductService() {
        if (!::viaductSchemaRegistryConfiguration.isInitialized) {
            viaductSchemaRegistryConfiguration = SchemaRegistryConfiguration.fromSdl(sdl, customScalars = customScalars.toList(), fullSchemaIds = listOf(defaultSchemaId))
            viaductBuilder.withSchemaRegistryConfiguration(viaductSchemaRegistryConfiguration)
        }
        if (!::viaductService.isInitialized) {
            try {
                viaductService = viaductBuilder.build()
            } catch (t: Throwable) {
                throw RuntimeException("Failed to build Viaduct service", t)
            }
        }
    }
}
