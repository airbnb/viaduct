@file:Suppress("ForbiddenImport")
@file:OptIn(VisibleForTest::class, InternalApi::class)

package viaduct.tenant.runtime.fixtures

import com.google.inject.Guice
import com.google.inject.Injector
import viaduct.api.Resolver
import viaduct.api.bootstrap.ViaductTenantAPIBootstrapper
import viaduct.api.internal.NodeResolverFor
import viaduct.api.internal.ResolverFor
import viaduct.api.reflect.Type
import viaduct.api.types.NodeObject
import viaduct.apiannotations.InternalApi
import viaduct.apiannotations.VisibleForTest
import viaduct.engine.api.spi.TenantModuleBootstrapper
import viaduct.service.api.spi.TenantAPIBootstrapperBuilder
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.tenant.runtime.bootstrap.GuiceTenantCodeInjector
import viaduct.tenant.runtime.bootstrap.TenantPackageInfo
import viaduct.tenant.runtime.bootstrap.ViaductTenantResolverClassFinderFactory

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
abstract class FeatureAppTestBase : AbstractFeatureAppTestBase() {
    open lateinit var sdl: String
        protected set

    /**
     * When true, validates before build that all schema-declared resolvers have corresponding
     * @Resolver-annotated implementation classes. This catches the common mistake of declaring
     * @resolver in the schema but forgetting to implement the resolver class.
     *
     * Override to false in tests that intentionally omit resolver implementations
     * (e.g., tenant filtering tests).
     */
    protected open val validateResolverCompleteness: Boolean = true

    private val injector: Injector by lazy { Guice.createInjector() }
    protected val guiceTenantCodeInjector by lazy { GuiceTenantCodeInjector(injector) }

    // GlobalID codec for creating GlobalID strings in tests
    private val globalIdCodec = GlobalIDCodecDefault

    // package name of the derived class
    private val derivedClassPackagePrefix: String =
        this::class.java.`package`?.name ?: throw RuntimeException(
            "Unable to read package name from subclass ${this::class.simpleName}"
        )

    // resolver class finder factory for feature test app use case
    private val tenantResolverClassFinderFactory = ViaductTenantResolverClassFinderFactory(
        grtPackagePrefix = derivedClassPackagePrefix
    )

    protected open val viaductTenantAPIBootstrapperBuilder =
        ViaductTenantAPIBootstrapper.Builder()
            .tenantCodeInjector(guiceTenantCodeInjector)
            .tenantResolverClassFinderFactory(tenantResolverClassFinderFactory)
            .tenantPackagePrefix(derivedClassPackagePrefix)

    override fun sdl(): String = sdl

    override fun createBootstrapperBuilder(): TenantAPIBootstrapperBuilder<TenantModuleBootstrapper> = viaductTenantAPIBootstrapperBuilder

    override fun onBeforeBuild() {
        if (validateResolverCompleteness) {
            validateResolverImplementations()
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
    ): String = globalIdCodec.serialize(type.name, internalId)

    /**
     * Helper function to get internalId from a GlobalID string.
     */
    fun <T : NodeObject> getInternalId(globalID: String): String {
        val (_, internalId) = globalIdCodec.deserialize(globalID)
        return internalId
    }

    /**
     * Validates that all generated resolver base classes have corresponding implementation classes.
     *
     * When the schema declares @resolver on a field or type, codegen generates a base class
     * (annotated with @ResolverFor or @NodeResolverFor). This method checks that each base class
     * has at least one implementation:
     * - Field resolvers: must have a subclass annotated with @Resolver
     * - Node resolvers: must have at least one subclass
     *
     * Without this check, a missing resolver is silently skipped during bootstrapping and the
     * field returns null at query time with no indication of what went wrong.
     */
    private fun validateResolverImplementations() {
        val classFinder = tenantResolverClassFinderFactory.create(TenantPackageInfo(derivedClassPackagePrefix))
        val missingResolvers = mutableListOf<String>()

        // Check field resolvers (@ResolverFor base classes need a @Resolver subclass).
        // Exclude built-in Viaduct resolvers (Query.node, Query.nodes) that are provided
        // by ViaductNodeResolverAPIBootstrapper rather than by tenant @Resolver classes.
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

        // Check node resolvers (@NodeResolverFor base classes need any subclass)
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
 * Thrown when a FeatureAppTest schema declares @resolver on fields or types but no corresponding
 * resolver implementation class is found.
 *
 * Each field marked with @resolver in the schema requires a class annotated with @Resolver
 * that extends the generated resolver base class (e.g., QueryResolvers.MyField for field resolvers,
 * NodeResolvers.MyType for node resolvers).
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
