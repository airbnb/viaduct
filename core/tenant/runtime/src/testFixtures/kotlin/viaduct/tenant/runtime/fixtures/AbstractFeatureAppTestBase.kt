@file:Suppress("ForbiddenImport")

package viaduct.tenant.runtime.fixtures

import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import viaduct.engine.api.spi.TenantModuleBootstrapper
import viaduct.service.ViaductBuilder
import viaduct.service.api.ExecutionInput
import viaduct.service.api.ExecutionResult
import viaduct.service.api.SchemaId
import viaduct.service.api.spi.TenantAPIBootstrapperBuilder
import viaduct.service.api.spi.mocks.MockFlagManager
import viaduct.service.runtime.SchemaConfiguration
import viaduct.service.runtime.StandardViaduct

/**
 * Shared abstract base class for testing GraphQL feature applications with Viaduct.
 *
 * Provides the common test lifecycle, builder wiring, and query execution logic
 * used by both the Kotlin tenant runtime and Java API runtime test bases.
 *
 * Subclasses must implement:
 * - [sdl] to provide the GraphQL schema text
 * - [createBootstrapperBuilder] to provide the tenant API bootstrapper
 *
 * Subclasses may override:
 * - [onBeforeBuild] to add pre-build validation (e.g., resolver completeness checks)
 * - [execute], [defaultSchemaId], [getScopeConfig] for custom behavior
 */
abstract class AbstractFeatureAppTestBase {
    /**
     * Returns the GraphQL SDL schema text for this test.
     */
    protected abstract fun sdl(): String

    /**
     * Creates the [TenantAPIBootstrapperBuilder] used to bootstrap resolvers.
     * Kotlin subclasses return a `ViaductTenantAPIBootstrapper.Builder`;
     * Java subclasses return a `MockTenantAPIBootstrapperBuilder` wrapper.
     */
    protected abstract fun createBootstrapperBuilder(): TenantAPIBootstrapperBuilder<TenantModuleBootstrapper>

    /**
     * Hook called just before [ViaductBuilder.build]. Override to add pre-build
     * validation (e.g., resolver completeness checks).
     */
    protected open fun onBeforeBuild() {}

    private val flagManager = MockFlagManager()

    protected lateinit var viaductBuilder: ViaductBuilder
    lateinit var viaductSchemaConfiguration: SchemaConfiguration
    lateinit var viaductService: StandardViaduct

    fun withViaductBuilder(builderUpdate: ViaductBuilder.() -> Unit) {
        viaductBuilder.apply(builderUpdate)
    }

    fun withSchemaConfiguration(config: SchemaConfiguration) {
        viaductBuilder = viaductBuilder.withSchemaConfiguration(config)
        viaductSchemaConfiguration = config
    }

    @BeforeEach
    open fun initViaductBuilder() {
        if (!::viaductBuilder.isInitialized) {
            viaductBuilder = ViaductBuilder()
                .withFlagManager(flagManager)
                .withTenantAPIBootstrapperBuilder(createBootstrapperBuilder())
        }
    }

    /**
     * Executes a query against the test application.
     *
     * @param query The GraphQL query to execute.
     * @param variables The variables to use for the query.
     * @param schemaId The schema ID to use.
     * @param requestContext Optional request context.
     * @return The result of the query execution.
     */
    @JvmOverloads
    open fun execute(
        query: String,
        variables: Map<String, Any?> = mapOf(),
        schemaId: SchemaId = defaultSchemaId(),
        requestContext: Any? = null,
    ): ExecutionResult {
        return runBlocking {
            tryBuildViaductService()
            val executionInput = ExecutionInput.create(
                operationText = query,
                variables = variables,
                requestContext = requestContext,
            )
            val result = viaductService.executeAsync(executionInput, schemaId).await()
            result
        }
    }

    open fun defaultSchemaId(): SchemaId = SchemaId.Full

    open fun getScopeConfig(): Set<SchemaConfiguration.ScopeConfig> = emptySet()

    /**
     * Attempts to build the [StandardViaduct] instance if it has not been initialized yet.
     */
    @Suppress("TooGenericExceptionCaught")
    fun tryBuildViaductService() {
        if (!::viaductSchemaConfiguration.isInitialized) {
            viaductSchemaConfiguration = SchemaConfiguration.fromSdl(sdl(), scopes = getScopeConfig())
            viaductBuilder.withSchemaConfiguration(viaductSchemaConfiguration)
        }
        if (!::viaductService.isInitialized) {
            onBeforeBuild()
            try {
                viaductService = viaductBuilder.build()
            } catch (t: Throwable) {
                throw RuntimeException("Failed to build Viaduct service", t)
            }
        }
    }
}
