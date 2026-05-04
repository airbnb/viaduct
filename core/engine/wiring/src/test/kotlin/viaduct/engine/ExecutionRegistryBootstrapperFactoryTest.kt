package viaduct.engine

import java.net.URL
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.apiannotations.VisibleForTest
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.bootstrap.executionregistry.FieldEntry
import viaduct.engine.api.bootstrap.executionregistry.NodeEntry
import viaduct.engine.api.spi.ExecutorFactory
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.service.api.spi.TenantCodeInjector

@OptIn(VisibleForTest::class)
class ExecutionRegistryBootstrapperFactoryTest {
    private val injector = TenantCodeInjector.Naive

    @Test
    fun `no prefix loads all registry files`() =
        runTest {
            val bootstrapper = BootstrapperFactory.fromResources(injector)
            val count = bootstrapper.tenantModuleBootstrappers().toList().size
            assert(count >= 3) { "Expected at least 3 bootstrappers (alpha, beta, gamma), got $count" }
        }

    @Test
    fun `prefix com-example loads only matching files`() =
        runTest {
            val bootstrapper = BootstrapperFactory.fromResources(injector, packagePrefix = "com.example")
            assertEquals(2, bootstrapper.tenantModuleBootstrappers().toList().size)
        }

    @Test
    fun `prefix com-example-alpha loads only the exact matching file`() =
        runTest {
            val bootstrapper = BootstrapperFactory.fromResources(injector, packagePrefix = "com.example.alpha")
            assertEquals(1, bootstrapper.tenantModuleBootstrappers().toList().size)
        }

    @Test
    fun `non-matching prefix returns empty bootstrapper`() =
        runTest {
            val bootstrapper = BootstrapperFactory.fromResources(injector, packagePrefix = "com.nomatch")
            assertEquals(0, bootstrapper.tenantModuleBootstrappers().toList().size)
        }
}

class WiringTestExecutorFactory(
    @Suppress("UNUSED_PARAMETER") injector: TenantCodeInjector,
    @Suppress("UNUSED_PARAMETER") moduleName: String,
    @Suppress("UNUSED_PARAMETER") configUrl: URL,
) : ExecutorFactory {
    override fun createFieldResolverExecutor(
        configData: FieldEntry,
        schema: ViaductSchema
    ): FieldResolverExecutor {
        throw UnsupportedOperationException("not needed for bootstrapper factory tests")
    }

    override fun createNodeResolverExecutor(
        configData: NodeEntry,
        schema: ViaductSchema
    ): NodeResolverExecutor {
        throw UnsupportedOperationException("not needed for bootstrapper factory tests")
    }
}
