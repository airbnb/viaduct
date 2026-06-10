@file:Suppress("ForbiddenImport", "DEPRECATION", "TYPEALIAS_EXPANSION_DEPRECATION") // for imports of legacy bootstrap shim
@file:OptIn(VisibleForTest::class, InternalApi::class)

package viaduct.api.testing.featureapp

import com.google.inject.Guice
import com.google.inject.Injector
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import viaduct.api.internal.NodeResolverFor
import viaduct.api.internal.ResolverFor
import viaduct.api.reflect.Type
import viaduct.api.resolver.Resolver
import viaduct.api.types.NodeObject
import viaduct.apiannotations.InternalApi
import viaduct.apiannotations.VisibleForTest
import viaduct.engine.BootstrapperFactory
import viaduct.engine.api.spi.LegacyTenantModuleBootstrapper
import viaduct.service.api.spi.SharedTenantModuleBootstrapper
import viaduct.service.api.spi.TenantAPIBootstrapperBuilder
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.tenant.runtime.bootstrap.GuiceCodeInjector
import viaduct.tenant.runtime.bootstrap.TenantPackageInfo
import viaduct.tenant.runtime.bootstrap.ViaductTenantResolverClassFinderFactory

/**
 * Contract test base class for Kotlin tenant resolvers.
 *
 * Provides the Kotlin-specific bootstrapper wiring: Guice injection, resolver class
 * discovery, resolver completeness validation, and GlobalID helpers.
 *
 * Extend this class in contract tests that define `@TestSchema` and `@Test` methods.
 * Subclasses provide resolver implementations.
 */
abstract class KotlinFeatureAppTestContractBase : AbstractFeatureAppTestContractBase() {
    /**
     * When true, validates before build that all schema-declared resolvers have corresponding
     * @Resolver-annotated implementation classes.
     *
     * Override to false in tests that intentionally omit resolver implementations.
     */
    protected open val validateResolverCompleteness: Boolean = true

    private val injector: Injector by lazy { Guice.createInjector(guiceModules()) }
    protected val guiceCodeInjector by lazy { GuiceCodeInjector(injector) }

    private val globalIdCodec = GlobalIDCodecDefault

    private val derivedClassPackagePrefix: String =
        this::class.java.`package`?.name ?: throw RuntimeException(
            "Unable to read package name from subclass ${this::class.simpleName}"
        )

    private val tenantResolverClassFinderFactory = ViaductTenantResolverClassFinderFactory(
        grtPackagePrefix = derivedClassPackagePrefix
    )

    @BeforeEach
    fun skipIfFileBasedRegistryAbsent() {
        // A subclass that overrides createBootstrapperBuilder supplies its own bootstrapper
        // and does not depend on the file-based registry being present. ( ie x:javaapi tests override file based bootstrapping )
        val overridesBootstrapper = generateSequence<Class<*>>(this::class.java) { it.superclass }
            .takeWhile { it != KotlinFeatureAppTestContractBase::class.java }
            .any { cls -> cls.declaredMethods.any { it.name == "createBootstrapperBuilder" } }
        if (overridesBootstrapper) return
        val registryPath = "META-INF/viaduct/modules/$derivedClassPackagePrefix.json"
        val isOnClasspath = Thread.currentThread().contextClassLoader.getResource(registryPath) != null
        assumeTrue(isOnClasspath, "Skipping: registry not on classpath ($registryPath)")
    }

    override fun createBootstrapperBuilder(): TenantAPIBootstrapperBuilder<LegacyTenantModuleBootstrapper> =
        object : TenantAPIBootstrapperBuilder<LegacyTenantModuleBootstrapper> {
            override fun create() =
                BootstrapperFactory.fromResources(
                    tenantModuleBootstrapper = SharedTenantModuleBootstrapper(guiceCodeInjector),
                    packagePrefix = derivedClassPackagePrefix,
                    grtPackagePrefix = derivedClassPackagePrefix,
                )
        }

    override fun onBeforeBuild() {
        if (validateResolverCompleteness) {
            validateResolverImplementations()
        }
    }

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

    private fun validateResolverImplementations() {
        val classFinder = tenantResolverClassFinderFactory.create(TenantPackageInfo(derivedClassPackagePrefix))
        val missingResolvers = mutableListOf<String>()

        val builtInResolverFields = setOf("Query" to "node", "Query" to "nodes")
        for (baseClass in classFinder.resolverClassesInPackage()) {
            val annotation = baseClass.annotations.firstOrNull { it is ResolverFor } as? ResolverFor
                ?: continue
            if ((annotation.typeName to annotation.fieldName) in builtInResolverFields) continue
            val implementations = classFinder.getSubTypesOf(baseClass)
                .filter { it.isAnnotationPresent(Resolver::class.java) }
            if (implementations.isEmpty()) {
                missingResolvers.add("${annotation.typeName}.${annotation.fieldName}")
            }
        }

        for (baseClass in classFinder.nodeResolverForClassesInPackage()) {
            val annotation = baseClass.annotations.firstOrNull { it is NodeResolverFor } as? NodeResolverFor
                ?: continue
            val implementations = classFinder.getSubTypesOf(baseClass)
            if (implementations.isEmpty()) {
                missingResolvers.add("Node(${annotation.typeName})")
            }
        }

        if (missingResolvers.isNotEmpty()) {
            throw MissingResolverImplementationException(missingResolvers)
        }
    }
}

/**
 * Thrown when a contract test schema declares @resolver on fields or types but no corresponding
 * resolver implementation class is found.
 */
class MissingResolverImplementationException(
    missingResolvers: List<String>
) : RuntimeException(
        buildString {
            append("Missing @Resolver implementation for schema-declared resolvers: ")
            append(missingResolvers.joinToString(", "))
            append(
                ". Each field or type with @resolver in the schema must have a corresponding " +
                    "class annotated with @Resolver that extends the generated resolver base class."
            )
        }
    )
