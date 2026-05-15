@file:Suppress("ForbiddenImport", "DEPRECATION", "TYPEALIAS_EXPANSION_DEPRECATION") // for imports of legacy bootstrap shim
@file:OptIn(viaduct.apiannotations.VisibleForTest::class)

package viaduct.tenant.runtime.fixtures

import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import viaduct.apiannotations.InternalApi
import viaduct.engine.api.spi.LegacyTenantModuleBootstrapper
import viaduct.service.SchemaScopeInfo
import viaduct.service.api.ExecutionInput
import viaduct.service.api.ExecutionResult
import viaduct.service.api.SchemaId
import viaduct.service.api.Viaduct
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
@OptIn(InternalApi::class)
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
    protected abstract fun createBootstrapperBuilder(): TenantAPIBootstrapperBuilder<LegacyTenantModuleBootstrapper>

    /**
     * Hook called just before build. Override to add pre-build
     * validation (e.g., resolver completeness checks).
     */
    protected open fun onBeforeBuild() {}

    private val flagManager = MockFlagManager()

    protected lateinit var viaductBuilder: StandardViaduct.Builder
    lateinit var viaductService: Viaduct

    /**
     * Safe to call from test methods and subclass `@BeforeEach` methods because JUnit runs
     * [initViaductBuilder] before those hooks. Do not call this from property initializers,
     * constructors, `init {}` blocks, or any custom setup path that bypasses JUnit lifecycle
     * callbacks, or [viaductBuilder] will not be initialized yet.
     */
    fun withViaductBuilder(builderUpdate: StandardViaduct.Builder.() -> Unit) {
        viaductBuilder.apply(builderUpdate)
    }

    /**
     * Creates a [SchemaConfiguration] from a JSON string in the same format as the
     * META-INF/viaduct/schema-scoping.json resource file. All non-FULL declared schemas
     * are eagerly registered. Intended for tests that want to avoid classpath resource setup.
     */
    protected fun configFromScopeJson(json: String): SchemaConfiguration =
        SchemaConfiguration.forTesting(json)

    /**
     * Configures scoped schemas for the test. Each [SchemaScopeInfo] binds a schema name
     * to a set of scope IDs. Replaces the default (unscoped) schema configuration.
     */
    fun withScopedSchemas(scopedSchemas: List<SchemaScopeInfo>) {
        val scopeConfigs = scopedSchemas.map {
            SchemaConfiguration.ScopeConfig(it.schemaId.id, it.scopesToApply)
        }.toSet()
        viaductBuilder = viaductBuilder.withSchemaConfiguration(
            SchemaConfiguration.fromSdl(sdl(), scopes = scopeConfigs)
        )
    }

    @BeforeEach
    open fun initViaductBuilder() {
        if (!::viaductBuilder.isInitialized) {
            viaductBuilder = StandardViaduct.Builder()
                .withFlagManager(flagManager)
                .withTenantAPIBootstrapperBuilder(createBootstrapperBuilder())
                .withSchemaConfiguration(SchemaConfiguration.fromSdl(sdl()))
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

    /**
     * Attempts to build the [Viaduct] instance if it has not been initialized yet.
     */
    @Suppress("TooGenericExceptionCaught")
    fun tryBuildViaductService() {
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
