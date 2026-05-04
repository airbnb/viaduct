@file:Suppress("ForbiddenImport")

package viaduct.api.testing.featureapp

import com.google.inject.Module
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import viaduct.api.testing.TestSchema
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
 * Abstract base class for contract-style feature-app tests.
 *
 * The schema is provided via the [TestSchema] annotation (required). Subclasses
 * provide language-specific bootstrapper wiring by implementing [createBootstrapperBuilder].
 *
 * This class provides the common test lifecycle, builder wiring, and query execution
 * logic shared by both the Kotlin and Java contract test bases.
 */
abstract class AbstractFeatureAppTestContractBase {
    private val sdl: String = requireNotNull(
        this::class.java.getAnnotation(TestSchema::class.java)?.value?.trimIndent()
    ) {
        "${this::class.simpleName} (or a superclass) must be annotated with @TestSchema"
    }

    /**
     * Returns the GraphQL SDL schema text from the @TestSchema annotation.
     */
    open fun sdl(): String = sdl

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

    /**
     * Override to provide additional Guice modules for the injector that creates
     * resolver instances. Use this to inject test state into resolver classes.
     *
     * Called lazily when the bootstrapper is first accessed (typically at
     * [initViaductBuilder] time), not during construction. Safe to reference
     * `this` — the subclass is fully initialized by the time this is called.
     */
    protected open fun guiceModules(): List<Module> = emptyList()

    private val flagManager = MockFlagManager()

    protected lateinit var viaductBuilder: ViaductBuilder
    lateinit var viaductSchemaConfiguration: SchemaConfiguration
    lateinit var viaductService: StandardViaduct

    /**
     * Safe to call from test methods and subclass `@BeforeEach` methods because JUnit runs
     * [initViaductBuilder] before those hooks. Do not call this from property initializers,
     * constructors, `init {}` blocks, or any custom setup path that bypasses JUnit lifecycle
     * callbacks, or [viaductBuilder] will not be initialized yet.
     */
    fun withViaductBuilder(builderUpdate: ViaductBuilder.() -> Unit) {
        viaductBuilder.apply(builderUpdate)
    }

    /**
     * Safe to call from test methods and subclass `@BeforeEach` methods because JUnit runs
     * [initViaductBuilder] first. Do not call this from property initializers, constructors,
     * `init {}` blocks, or any setup path that runs before or instead of [initViaductBuilder].
     */
    fun withSchemaConfiguration(config: SchemaConfiguration) {
        viaductBuilder = viaductBuilder.withSchemaConfiguration(config)
        viaductSchemaConfiguration = config
    }

    @BeforeEach
    open fun initViaductBuilder() {
        if (!::viaductBuilder.isInitialized) {
            viaductBuilder = ViaductBuilder()
                .withFlagManager(flagManager)
                .withLenientResolverValidation()
                .withTenantAPIBootstrapperBuilder(createBootstrapperBuilder())
        }
    }

    /**
     * Executes a query against the test application.
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
