package viaduct.engine

import java.net.URL
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.bootstrap.executionregistry.FieldEntryConfig
import viaduct.engine.api.bootstrap.executionregistry.NodeEntryConfig
import viaduct.engine.api.spi.ExecutorFactory
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.service.api.spi.CodeInjector
import viaduct.service.api.spi.SharedTenantModuleInjectorFactory

class ExecutionRegistryBootstrapperFactoryTest {
    private val injector = CodeInjector.Naive

    @Test
    fun `no prefix loads all registry files`() =
        runTest {
            val bootstrapper = BootstrapperFactory.fromResources(SharedTenantModuleInjectorFactory(injector))
            val count = bootstrapper.tenantModuleBootstrappers().toList().size
            assert(count >= 3) { "Expected at least 3 bootstrappers (alpha, beta, gamma), got $count" }
        }

    @Test
    fun `prefix com-example loads only matching files`() =
        runTest {
            val bootstrapper = BootstrapperFactory.fromResources(
                tenantModuleInjectorFactory = SharedTenantModuleInjectorFactory(injector),
                packagePrefix = "com.example",
            )
            assertEquals(2, bootstrapper.tenantModuleBootstrappers().toList().size)
        }

    @Test
    fun `prefix com-example-alpha loads only the exact matching file`() =
        runTest {
            val bootstrapper = BootstrapperFactory.fromResources(
                tenantModuleInjectorFactory = SharedTenantModuleInjectorFactory(injector),
                packagePrefix = "com.example.alpha",
            )
            assertEquals(1, bootstrapper.tenantModuleBootstrappers().toList().size)
        }

    @Test
    fun `non-matching prefix returns empty bootstrapper`() =
        runTest {
            val bootstrapper = BootstrapperFactory.fromResources(
                tenantModuleInjectorFactory = SharedTenantModuleInjectorFactory(injector),
                packagePrefix = "com.nomatch",
            )
            assertEquals(0, bootstrapper.tenantModuleBootstrappers().toList().size)
        }

    @Test
    fun `custom injector is used through the shared tenant module injector factory`() =
        runTest {
            val customInjector = object : CodeInjector {
                override fun <T> getProvider(clazz: Class<T>) = throw UnsupportedOperationException("not needed for bootstrapper factory tests")
            }

            WiringTestExecutorFactory.lastInjector = null

            val bootstrapper = BootstrapperFactory.fromResources(
                tenantModuleInjectorFactory = SharedTenantModuleInjectorFactory(customInjector),
                packagePrefix = "com.example.alpha",
            )

            bootstrapper.tenantModuleBootstrappers()

            assertSame(customInjector, WiringTestExecutorFactory.lastInjector)
        }
}

class WiringTestExecutorFactory(
    injector: CodeInjector,
    @Suppress("UNUSED_PARAMETER") configUrl: URL,
) : ExecutorFactory {
    init {
        lastInjector = injector
    }

    override fun createFieldResolverExecutor(
        configData: FieldEntryConfig,
        schema: ViaductSchema
    ): FieldResolverExecutor {
        throw UnsupportedOperationException("not needed for bootstrapper factory tests")
    }

    override fun createNodeResolverExecutor(
        configData: NodeEntryConfig,
        schema: ViaductSchema
    ): NodeResolverExecutor {
        throw UnsupportedOperationException("not needed for bootstrapper factory tests")
    }

    companion object {
        var lastInjector: CodeInjector? = null
    }
}
