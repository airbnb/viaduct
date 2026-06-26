@file:Suppress("ForbiddenImport")

package viaduct.api.bootstrap

import com.google.inject.AbstractModule
import com.google.inject.Guice
import com.google.inject.Injector
import graphql.schema.GraphQLSchema
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.api.bootstrap.test.AFieldResolver
import viaduct.api.bootstrap.test.TestBatchNodeResolver
import viaduct.api.bootstrap.test.TestNodeResolver
import viaduct.api.bootstrap.test.TestTenantModule
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.api.spi.TenantAPIBootstrapper
import viaduct.engine.api.spi.TenantModuleBootstrapper
import viaduct.service.api.spi.CodeInjector
import viaduct.service.api.spi.InputStreamSource
import viaduct.service.api.spi.TenantModuleInjectorFactory
import viaduct.tenant.runtime.bootstrap.GuiceCodeInjector
import viaduct.tenant.runtime.bootstrap.TenantPackageFinder
import viaduct.tenant.runtime.bootstrap.TenantPackageInfo
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
    private lateinit var codeInjector: Injector
    private lateinit var tenantResolverClassFinder: TenantResolverClassFinder
    private lateinit var tenantAPIBootstrapper: TenantAPIBootstrapper
    private lateinit var tenantModuleBootstrappers: Iterable<TenantModuleBootstrapper>
    private lateinit var fieldResolverExecutors: Map<Pair<String, String>, FieldResolverExecutor>
    private lateinit var nodeResolverExecutors: Map<String, NodeResolverExecutor>

    fun mkSchema(sdl: String): GraphQLSchema {
        val tdr = SchemaParser().parse(sdl)
        return SchemaGenerator().makeExecutableSchema(tdr, RuntimeWiring.MOCKED_WIRING)
    }

    @BeforeEach
    fun setUp() {
        codeInjector = resolverInjector()

        injector =
            Guice.createInjector(
                object : AbstractModule() {
                    override fun configure() {
                        bind(GraphQLSchema::class.java).toInstance(schema.schema)
                        bind(TenantPackageFinder::class.java).toInstance(TestTenantPackageFinder(listOf(TestTenantModule::class)))
                        bind(CodeInjector::class.java).toInstance(GuiceCodeInjector(codeInjector))

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
                .tenantCodeInjector(GuiceCodeInjector(codeInjector))
                .tenantPackageFinder(injector.getInstance(TenantPackageFinder::class.java))
                .tenantResolverClassFinderFactory { tenantResolverClassFinder }
                .executorRegistryConfigSources(
                    listOf(
                        registryConfigSource(
                            tenantName = "viaduct/api/bootstrap/test",
                            bootstrapClassName = TestTenantBootstrapper::class.java.name,
                        )
                    )
                )
                .tenantModuleInjectorFactory(RecordingTenantModuleInjectorFactory(GuiceCodeInjector(codeInjector)))
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
        val tenantAFieldResolver = codeInjector.getInstance(AFieldResolver::class.java)
        val systemAFieldResolver = injector.getInstance(AFieldResolver::class.java)
        assertSame(tenantAFieldResolver, codeInjector.getInstance(AFieldResolver::class.java))
        assertSame(systemAFieldResolver, injector.getInstance(AFieldResolver::class.java))
        assertNotSame(tenantAFieldResolver, systemAFieldResolver)
    }

    @Test
    fun `ensure the tenant code injector is used for fields`() {
        assertSame(
            codeInjector.getInstance(AFieldResolver::class.java),
            (fieldResolverExecutors[Pair("TestType", "aField")] as FieldUnbatchedResolverExecutorImpl)
                .resolver.get()
        )
    }

    @Test
    fun `ensure the tenant code injector is used for nodes`() {
        assertSame(
            codeInjector.getInstance(TestNodeResolver::class.java),
            (nodeResolverExecutors["TestNode"] as NodeUnbatchedResolverExecutorImpl)
                .resolver.get()
        )
    }

    @Test
    fun `ensure the tenant code injector is used for batched nodes`() {
        assertSame(
            codeInjector.getInstance(TestBatchNodeResolver::class.java),
            (nodeResolverExecutors["TestBatchNode"] as NodeBatchResolverExecutorImpl)
                .resolver.get()
        )
    }

    @Test
    @Suppress("DEPRECATION")
    fun `scanner bootstrapper without registry configs keeps configured code injector`() {
        val bootstrapper = ViaductTenantAPIBootstrapper.Builder()
            .tenantCodeInjector(GuiceCodeInjector(codeInjector))
            .tenantPackageFinder(injector.getInstance(TenantPackageFinder::class.java))
            .tenantResolverClassFinderFactory { tenantResolverClassFinder }
            .create()

        val fieldResolvers = runBlocking {
            bootstrapper
                .tenantModuleBootstrappers()
                .flatMap { it.fieldResolverExecutors(schema) }
                .toMap()
        }

        assertSame(
            codeInjector.getInstance(AFieldResolver::class.java),
            (fieldResolvers[Pair("TestType", "aField")] as FieldUnbatchedResolverExecutorImpl).resolver.get(),
        )
    }

    @Test
    fun `matching registry config uses child tenant injector for modern tenant bootstrapper`() {
        val childInjector = resolverInjector()
        val tenantModuleInjectorFactory = RecordingTenantModuleInjectorFactory(GuiceCodeInjector(childInjector))
        val tenantPackageFinder = TenantPackageFinder {
            setOf(TenantPackageInfo("com.airbnb.viaduct.data.contextualuser"))
        }
        val bootstrapper = createBootstrapper(
            tenantPackageFinder = tenantPackageFinder,
            executorRegistryConfigSources = listOf(
                registryConfigSource(
                    tenantName = "data/contextualuser",
                    bootstrapClassName = TestTenantBootstrapper::class.java.name,
                )
            ),
            tenantModuleInjectorFactory = tenantModuleInjectorFactory,
        )

        val fieldResolvers = runBlocking {
            bootstrapper
                .tenantModuleBootstrappers()
                .flatMap { it.fieldResolverExecutors(schema) }
                .toMap()
        }

        assertEquals(
            listOf("data/contextualuser" to TestTenantBootstrapper::class.java),
            tenantModuleInjectorFactory.bootstrappedTenants,
        )
        assertTrue(tenantModuleInjectorFactory.finalized)
        assertSame(
            childInjector.getInstance(AFieldResolver::class.java),
            (fieldResolvers[Pair("TestType", "aField")] as FieldUnbatchedResolverExecutorImpl).resolver.get(),
        )
        assertNotSame(
            codeInjector.getInstance(AFieldResolver::class.java),
            (fieldResolvers[Pair("TestType", "aField")] as FieldUnbatchedResolverExecutorImpl).resolver.get(),
        )
    }

    @Test
    @Suppress("DEPRECATION")
    fun `missing tenant injector factory fails modern tenant bootstrapper when registry configs are provided`() {
        val bootstrapper = ViaductTenantAPIBootstrapper.Builder()
            .tenantCodeInjector(GuiceCodeInjector(codeInjector))
            .tenantPackageFinder(injector.getInstance(TenantPackageFinder::class.java))
            .tenantResolverClassFinderFactory { tenantResolverClassFinder }
            .executorRegistryConfigSources(
                listOf(
                    registryConfigSource(
                        tenantName = "viaduct/api/bootstrap/test",
                        bootstrapClassName = TestTenantBootstrapper::class.java.name,
                    )
                )
            )
            .create()

        val exception = assertThrows(IllegalStateException::class.java) {
            runBlocking { bootstrapper.tenantModuleBootstrappers() }
        }

        assertTrue(exception.message!!.contains("tenantModuleInjectorFactory is required"))
    }

    @Test
    fun `missing registry config fails modern tenant bootstrapper when tenant injector factory is configured`() {
        val childInjector = resolverInjector()
        val tenantModuleInjectorFactory = RecordingTenantModuleInjectorFactory(GuiceCodeInjector(childInjector))
        val bootstrapper = createBootstrapper(
            executorRegistryConfigSources = listOf(
                registryConfigSource(
                    tenantName = "data/other",
                    bootstrapClassName = TestTenantBootstrapper::class.java.name,
                )
            ),
            tenantModuleInjectorFactory = tenantModuleInjectorFactory,
        )

        val exception = assertThrows(IllegalStateException::class.java) {
            runBlocking { bootstrapper.tenantModuleBootstrappers() }
        }

        assertTrue(exception.message!!.contains("Missing execution registry config"))
        assertTrue(tenantModuleInjectorFactory.bootstrappedTenants.isEmpty())
        assertEquals(false, tenantModuleInjectorFactory.finalized)
    }

    @Test
    fun `missing bootstrap class fails modern tenant bootstrapper when tenant injector factory is configured`() {
        val childInjector = resolverInjector()
        val tenantModuleInjectorFactory = RecordingTenantModuleInjectorFactory(GuiceCodeInjector(childInjector))
        val bootstrapper = createBootstrapper(
            tenantPackageFinder = TenantPackageFinder {
                setOf(TenantPackageInfo("com.airbnb.viaduct.data.contextualuser"))
            },
            executorRegistryConfigSources = listOf(
                registryConfigSourceWithoutBootstrapClass(tenantName = "data/contextualuser")
            ),
            tenantModuleInjectorFactory = tenantModuleInjectorFactory,
        )

        val exception = assertThrows(IllegalStateException::class.java) {
            runBlocking { bootstrapper.tenantModuleBootstrappers() }
        }

        assertTrue(exception.message!!.contains("Missing bootstrapClass"))
        assertTrue(tenantModuleInjectorFactory.bootstrappedTenants.isEmpty())
        assertEquals(false, tenantModuleInjectorFactory.finalized)
    }

    private fun resolverInjector(): Injector =
        Guice.createInjector(
            object : AbstractModule() {
                override fun configure() {
                    bind(AFieldResolver::class.java).`in`(Singleton::class.java)
                    bind(TestBatchNodeResolver::class.java).`in`(Singleton::class.java)
                    bind(TestNodeResolver::class.java).`in`(Singleton::class.java)
                }
            }
        )

    @Suppress("DEPRECATION")
    private fun createBootstrapper(
        tenantPackageFinder: TenantPackageFinder = injector.getInstance(TenantPackageFinder::class.java),
        executorRegistryConfigSources: List<InputStreamSource>,
        tenantModuleInjectorFactory: TenantModuleInjectorFactory,
    ): TenantAPIBootstrapper =
        ViaductTenantAPIBootstrapper.Builder()
            .tenantCodeInjector(GuiceCodeInjector(codeInjector))
            .tenantPackageFinder(tenantPackageFinder)
            .tenantResolverClassFinderFactory { tenantResolverClassFinder }
            .executorRegistryConfigSources(executorRegistryConfigSources)
            .tenantModuleInjectorFactory(tenantModuleInjectorFactory)
            .create()

    private fun registryConfigSource(
        tenantName: String,
        bootstrapClassName: String,
    ) = InputStreamSource.fromString(
        """
        {
          "version": "1",
          "tenantName": "$tenantName",
          "executorFactory": "unused",
          "bootstrapClass": "$bootstrapClassName"
        }
        """.trimIndent(),
        name = tenantName,
    )

    private fun registryConfigSourceWithoutBootstrapClass(tenantName: String) =
        InputStreamSource.fromString(
            """
            {
              "version": "1",
              "tenantName": "$tenantName",
              "executorFactory": "unused"
            }
            """.trimIndent(),
            name = tenantName,
        )

    private class RecordingTenantModuleInjectorFactory(
        private val codeInjector: CodeInjector,
    ) : TenantModuleInjectorFactory {
        val bootstrappedTenants = mutableListOf<Pair<String, Class<*>?>>()
        var finalized = false

        override suspend fun bootstrap(
            tenantName: String,
            tenantBootstrapClass: Class<*>?,
        ): CodeInjector {
            bootstrappedTenants += tenantName to tenantBootstrapClass
            return codeInjector
        }

        override suspend fun finalize() {
            finalized = true
        }
    }

    private class TestTenantBootstrapper
}
