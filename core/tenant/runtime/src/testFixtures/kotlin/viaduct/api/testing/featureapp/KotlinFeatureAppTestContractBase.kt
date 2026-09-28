@file:Suppress("ForbiddenImport")
@file:OptIn(VisibleForTest::class, InternalApi::class)

package viaduct.api.testing.featureapp

import com.google.inject.Guice
import com.google.inject.Injector
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import viaduct.api.reflect.Type
import viaduct.api.types.NodeObject
import viaduct.apiannotations.InternalApi
import viaduct.apiannotations.VisibleForTest
import viaduct.engine.api.bootstrap.executionregistry.ModuleConfigSource
import viaduct.engine.runtime.tenantloading.ExecutionRegistryConfigSourceCollector
import viaduct.service.api.spi.SharedTenantModuleInjectorFactory
import viaduct.service.api.spi.TenantModuleInjectorFactory
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.tenant.runtime.bootstrap.GuiceCodeInjector

/**
 * Contract test base class for Kotlin tenant resolvers.
 *
 * Provides Guice injection, generated module configs, resolver validation, and GlobalID helpers.
 *
 * Extend this class in contract tests that define `@TestSchema` and `@Test` methods.
 * Subclasses provide resolver implementations.
 */
abstract class KotlinFeatureAppTestContractBase : AbstractFeatureAppTestContractBase() {
    override val validateResolverCompleteness: Boolean = true

    private val injector: Injector by lazy { Guice.createInjector(guiceModules()) }
    protected val guiceCodeInjector by lazy { GuiceCodeInjector(injector) }

    private val globalIdCodec = GlobalIDCodecDefault

    private val derivedClassPackagePrefix: String =
        this::class.java.`package`?.name ?: throw RuntimeException(
            "Unable to read package name from subclass ${this::class.simpleName}"
        )

    private val overridesBootstrapper: Boolean = generateSequence<Class<*>>(this::class.java) { it.superclass }
        .takeWhile { it != KotlinFeatureAppTestContractBase::class.java }
        .any { cls -> cls.declaredMethods.any { it.name == "moduleConfigSources" } }

    @BeforeEach
    fun failIfFileBasedRegistryAbsent() {
        if (overridesBootstrapper || !validateResolverCompleteness) return
        val registryPath = "META-INF/viaduct/modules/$derivedClassPackagePrefix.json"
        val resource = Thread.currentThread().contextClassLoader.getResource(registryPath)
        assertNotNull(resource) {
            "Contract test registry not found on classpath: $registryPath. " +
                "This means the KSP registry-extractor plugin did not run or its output was not wired " +
                "as a runtime_dep. Ensure your BUILD.bazel has: (1) the viaduct_tenant_registry_extractor_ksp_plugin " +
                "in kt_jvm_library plugins, (2) an assemble_tenant_module_config rule with the kt_jvm_library as a leaf, " +
                "and (3) the assembled registry in java_test runtime_deps."
        }
    }

    override fun moduleConfigSources(): List<ModuleConfigSource> = ExecutionRegistryConfigSourceCollector.fromResources(derivedClassPackagePrefix)

    override fun tenantModuleInjectorFactory(): TenantModuleInjectorFactory = SharedTenantModuleInjectorFactory(guiceCodeInjector)

    override fun grtPackagePrefix(): String = derivedClassPackagePrefix

    /**
     * Creates a GlobalID string for the given type and internal ID.
     */
    fun <T : NodeObject> createGlobalIdString(
        type: Type<T>,
        internalId: String
    ): String = globalIdCodec.serialize(type.name, internalId)

    /**
     * Helper function to get internalId from a GlobalID string.
     */
    fun <T : NodeObject> getInternalId(globalID: String): String {
        val (_, internalId) = globalIdCodec.deserialize(globalID)
        return internalId
    }
}
