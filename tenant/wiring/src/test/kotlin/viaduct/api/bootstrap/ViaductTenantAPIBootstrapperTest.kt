@file:Suppress("ForbiddenImport")

package viaduct.api.bootstrap

import com.google.inject.AbstractModule
import com.google.inject.Guice
import com.google.inject.Injector
import com.google.inject.Singleton
import graphql.schema.GraphQLSchema
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.api.bootstrap.test.AFieldResolver
import viaduct.api.bootstrap.test.TestBatchNodeResolver
import viaduct.api.bootstrap.test.TestNodeResolver
import viaduct.api.bootstrap.test.TestTenantModule
import viaduct.engine.api.FieldResolverExecutor
import viaduct.engine.api.NodeResolverExecutor
import viaduct.engine.api.TenantModuleBootstrapper
import viaduct.engine.api.ViaductSchema
import viaduct.service.api.spi.TenantCodeInjector
import viaduct.tenant.runtime.bootstrap.GuiceTenantCodeInjector
import viaduct.tenant.runtime.bootstrap.TenantPackageFinder
import viaduct.tenant.runtime.bootstrap.TenantResolverClassFinder
import viaduct.tenant.runtime.bootstrap.TestTenantPackageFinder
import viaduct.tenant.runtime.bootstrap.ViaductTenantResolverClassFinder
import viaduct.tenant.runtime.execution.FieldUnbatchedResolverExecutorImpl
import viaduct.tenant.runtime.execution.NodeBatchResolverExecutorImpl
import viaduct.tenant.runtime.execution.NodeUnbatchedResolverExecutorImpl

@OptIn(ExperimentalCoroutinesApi::class)
class ViaductTenantAPIBootstrapperTest {
    companion object {
        private const val PACKAGE_NAME = "viaduct.api.bootstrap.test"
    }

    private val schema = ViaductSchema(
        mkSchema(
            """
            type Query {
                foo: String
            }
            interface Node {
                id: ID!
            }
            type TestNode implements Node {
                id: ID!
            }
            type TestBatchNode implements Node {
                id: ID!
            }
            type TestType {
                aField: String @privacy(fullTimeEmployeeAccess: true)
                bIntField: Int @privacy(gandalfPermissions: ["test:permission"])
                parameterizedField(experiment: Boolean): Boolean # test field argument only
                cField(f1: String, f2: Int): String # test field argument and variable provider conflict
                dField: String # test variable provider only
                whenMappingsTest: String # test resolvers that include synthetic WhenMappings classes
            }
            directive @privacy(
                fullTimeEmployeeAccess: Boolean
                gandalfPermissions: [String!]
                gandalfAction: String
            ) on OBJECT | FIELD_DEFINITION
            """.trimIndent()
        )
    )

    private lateinit var injector: Injector
    private lateinit var tenantCodeInjector: Injector
    private lateinit var tenantResolverClassFinder: TenantResolverClassFinder
    private lateinit var tenantAPIBootstrapper: ViaductTenantAPIBootstrapper
    private lateinit var tenantModuleBootstrappers: Iterable<TenantModuleBootstrapper>
    private lateinit var fieldResolverExecutors: Map<Pair<String, String>, FieldResolverExecutor>
    private lateinit var nodeResolverExecutors: Map<String, NodeResolverExecutor>

    fun mkSchema(sdl: String): GraphQLSchema {
        val tdr = SchemaParser().parse(sdl)
        return SchemaGenerator().makeExecutableSchema(tdr, RuntimeWiring.MOCKED_WIRING)
    }

    @BeforeEach
    fun setUp() {
        tenantCodeInjector =
            Guice.createInjector(
                object : AbstractModule() {
                    override fun configure() {
                        bind(AFieldResolver::class.java).`in`(Singleton::class.java)
                        bind(TestBatchNodeResolver::class.java).`in`(Singleton::class.java)
                        bind(TestNodeResolver::class.java).`in`(Singleton::class.java)
                    }
                }
            )

        injector =
            Guice.createInjector(
                object : AbstractModule() {
                    override fun configure() {
                        bind(GraphQLSchema::class.java).toInstance(schema.schema)
                        bind(TenantPackageFinder::class.java).toInstance(TestTenantPackageFinder(listOf(TestTenantModule::class)))
                        bind(TenantCodeInjector::class.java).toInstance(GuiceTenantCodeInjector(tenantCodeInjector))

                        bind(AFieldResolver::class.java).`in`(Singleton::class.java)
                        bind(TestBatchNodeResolver::class.java).`in`(Singleton::class.java)
                        bind(TestNodeResolver::class.java).`in`(Singleton::class.java)
                    }
                }
            )

        tenantResolverClassFinder = ViaductTenantResolverClassFinder(
            tenantPackage = PACKAGE_NAME,
            grtPackagePrefix = "$PACKAGE_NAME.grts"
        )
        runBlocking {
            @Suppress("DEPRECATION")
            tenantAPIBootstrapper = ViaductTenantAPIBootstrapper.Builder()
                .tenantCodeInjector(GuiceTenantCodeInjector(tenantCodeInjector))
                .tenantPackageFinder(injector.getInstance(TenantPackageFinder::class.java))
                .tenantResolverClassFinderFactory { tenantResolverClassFinder }
                .create()

            tenantModuleBootstrappers = tenantAPIBootstrapper.tenantModuleBootstrappers()
            fieldResolverExecutors = tenantModuleBootstrappers.flatMap { it.fieldResolverExecutors(schema) }.toMap()
            nodeResolverExecutors = tenantModuleBootstrappers.flatMap { it.nodeResolverExecutors(schema) }.toMap()
        }
    }

    @Test
    fun `test successful creation of tenant bootstrappers`() {
        assertEquals(1, tenantModuleBootstrappers.count())
    }

    @Test
    fun `test successful creation of tenant resolvers`() {
        val resolverExecutorInt = fieldResolverExecutors[Pair("TestType", "bIntField")]
        assertTrue(resolverExecutorInt is FieldUnbatchedResolverExecutorImpl)

        val testNodeResolver = nodeResolverExecutors["TestNode"]
        assertTrue(testNodeResolver is NodeUnbatchedResolverExecutorImpl)
    }

    @Test
    fun `test missing types are not in registry`() {
        assertNull(nodeResolverExecutors["TestMissing"])
    }

    @Test
    fun `regression -- can bootstrap tenants that use WhenMappings`() {
        // When a function uses a when block that matches on enum values, the kotlin compiler
        // will optimize this by generating a synthetic "WhenMappings" class.
        // Some kotlin apis that attempt to read the annotations of this class
        // will fail with an error like:
        //   java.lang.UnsupportedOperationException: This class is an internal synthetic class generated by the
        //   Kotlin compiler, such as an anonymous class for a lambda, a SAM wrapper, a callable reference, etc.
        //   It's not a Kotlin class or interface, so the reflection library has no idea what declarations it has.
        //   Please use Java reflection to inspect this class:
        //   class com.airbnb.viaduct.presentation.demoapp.resolvers.DemoAppResolver$WhenMappings
        //
        // This error can surface during bootstrapping, when we are iterating over the nested classes of a tenant resolver
        // looking for a VariablesProvider.
        // This test ensures that we are able to bootstrap a resolver for which the kotlin compiler would generate
        // a WhenMappings
        //
        // see https://app.asana.com/0/1208357307661305/1208779075591645

        assertNotNull(fieldResolverExecutors[Pair("TestType", "whenMappingsTest")])
    }

    @Test
    fun `ensure injectors are working as assumed`() { // Test of the test setup
        val tenantAFieldResolver = tenantCodeInjector.getInstance(AFieldResolver::class.java)
        val systemAFieldResolver = injector.getInstance(AFieldResolver::class.java)
        assertSame(tenantAFieldResolver, tenantCodeInjector.getInstance(AFieldResolver::class.java))
        assertSame(systemAFieldResolver, injector.getInstance(AFieldResolver::class.java))
        assertNotSame(tenantAFieldResolver, systemAFieldResolver)
    }

    @Test
    fun `ensure the tenant code injector is used for fields`() {
        assertSame(
            tenantCodeInjector.getInstance(AFieldResolver::class.java),
            (fieldResolverExecutors[Pair("TestType", "aField")] as FieldUnbatchedResolverExecutorImpl)
                .resolver.get()
        )
    }

    @Test
    fun `ensure the tenant code injector is used for nodes`() {
        assertSame(
            tenantCodeInjector.getInstance(TestNodeResolver::class.java),
            (nodeResolverExecutors["TestNode"] as NodeUnbatchedResolverExecutorImpl)
                .resolver.get()
        )
    }

    @Test
    fun `ensure the tenant code injector is used for batched nodes`() {
        assertSame(
            tenantCodeInjector.getInstance(TestBatchNodeResolver::class.java),
            (nodeResolverExecutors["TestBatchNode"] as NodeBatchResolverExecutorImpl)
                .resolver.get()
        )
    }
}
