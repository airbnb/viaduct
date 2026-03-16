@file:Suppress("ForbiddenImport")

package viaduct.java.runtime.fixtures

import viaduct.engine.api.mocks.MockTenantAPIBootstrapper
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.TenantModuleBootstrapper
import viaduct.java.runtime.bridge.DefaultJavaResolverClassFinder
import viaduct.java.runtime.bridge.JavaModuleBootstrapper
import viaduct.service.api.SchemaId
import viaduct.service.api.mocks.MockTenantAPIBootstrapperBuilder
import viaduct.service.api.spi.TenantAPIBootstrapperBuilder
import viaduct.service.api.spi.TenantCodeInjector
import viaduct.tenant.runtime.fixtures.AbstractFeatureAppTestBase

/**
 * Base class for testing Java GraphQL feature applications with Viaduct.
 *
 * This class provides a test harness for Java resolvers, similar to the Kotlin
 * [viaduct.tenant.runtime.fixtures.FeatureAppTestBase] but for Java resolver implementations.
 *
 * Usage:
 * 1. Extend this class in your Java test
 * 2. Override the `getSdl()` method with your GraphQL schema
 * 3. Define your resolver implementations as inner classes annotated with @Resolver
 * 4. Use the `execute()` method to run queries against your implementation
 *
 * Example:
 * ```java
 * public class MyFeatureAppTest extends JavaFeatureAppTestBase {
 *     @Override
 *     public String getSdl() {
 *         return """
 *             extend type Query {
 *                 hello: String! @resolver
 *             }
 *         """;
 *     }
 *
 *     @Resolver
 *     public static class HelloResolver extends QueryResolvers.Hello {
 *         @Override
 *         public CompletableFuture<String> resolve(Context ctx) {
 *             return CompletableFuture.completedFuture("Hello, World!");
 *         }
 *     }
 *
 *     @Test
 *     public void testHelloQuery() {
 *         ExecutionResult result = execute("{ hello }");
 *         // Assert on result
 *     }
 * }
 * ```
 *
 * **Important**: GRTs are created per package namespace. Multiple tests in the same package will
 * share the same generated classes, which can cause conflicts. To avoid this, place each feature
 * test app in its own separate package.
 */
abstract class JavaFeatureAppTestBase : AbstractFeatureAppTestBase() {
    /**
     * Override this method to provide the GraphQL schema for your test.
     */
    abstract fun getSdl(): String

    // Package name of the derived class (e.g., "...enum_example.resolvers")
    private val derivedClassPackage: String =
        this::class.java.`package`?.name ?: throw RuntimeException(
            "Unable to read package name from subclass ${this::class.simpleName}"
        )

    // Parent package prefix covering both "resolvers" and "resolverbases" sibling packages
    // e.g., "viaduct.java.runtime.featureapp.enum_example" from "...enum_example.resolvers"
    // Override in subclasses to support flat package structure (test + GRTs in same package)
    protected open fun featureAppPackagePrefix(): String = derivedClassPackage.substringBeforeLast('.')

    protected open fun grtPackagePrefix(): String = "${featureAppPackagePrefix()}.grt"

    // Class finder for discovering Java resolvers in the feature app's packages
    private val classFinder by lazy {
        DefaultJavaResolverClassFinder(
            tenantPackage = featureAppPackagePrefix(),
            grtPackagePrefix = grtPackagePrefix()
        )
    }

    // Bootstrapper for Java resolvers
    private val bootstrapper by lazy {
        JavaModuleBootstrapper(classFinder, TenantCodeInjector.Naive)
    }

    override fun sdl(): String = getSdl()

    override fun createBootstrapperBuilder(): TenantAPIBootstrapperBuilder<TenantModuleBootstrapper> = MockTenantAPIBootstrapperBuilder(MockTenantAPIBootstrapper(listOf(bootstrapper)))

    /**
     * Returns the field resolver executor for a given type and field.
     * Useful for testing that required selections are properly wired.
     *
     * @param typeName The GraphQL type name (e.g., "Person")
     * @param fieldName The field name (e.g., "fullAddress")
     * @return The FieldResolverExecutor if found, null otherwise
     */
    protected fun getFieldResolverExecutor(
        typeName: String,
        fieldName: String
    ): FieldResolverExecutor? {
        tryBuildViaductService()
        val schema = viaductService.engineRegistry.getSchema(SchemaId.Full)
        val executors = bootstrapper.fieldResolverExecutors(schema)
        return executors.find { (coordinate, _) ->
            coordinate.first == typeName && coordinate.second == fieldName
        }?.second
    }
}
