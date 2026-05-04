package viaduct.engine.runtime.tenantloading

import java.net.URL
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.bootstrap.executionregistry.FieldEntry
import viaduct.engine.api.bootstrap.executionregistry.NodeEntry
import viaduct.engine.api.spi.ExecutorFactory
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.service.api.spi.TenantCodeInjector

class ExecutionRegistryTenantAPIBootstrapperTest {
    private val injector = TenantCodeInjector.Naive

    @Test
    fun `empty URL list returns empty iterable`() =
        runTest {
            val bootstrapper = ExecutionRegistryTenantAPIBootstrapper(injector, emptyList())
            assertEquals(0, bootstrapper.tenantModuleBootstrappers().count())
        }

    @Test
    fun `valid registry URL creates module bootstrapper with correct module name`() =
        runTest {
            val url = requireNotNull(
                Thread.currentThread().contextClassLoader
                    .getResource("META-INF/viaduct/modules/com.example.test.json")
            ) { "Test resource not found on classpath" }

            val bootstrapper = ExecutionRegistryTenantAPIBootstrapper(injector, listOf(url))
            assertEquals(1, bootstrapper.tenantModuleBootstrappers().toList().size)
            assertEquals("com.example.test", TestExecutorFactory.lastModuleName)
        }

    @Test
    fun `unknown executorFactory FQN in JSON throws ClassNotFoundException`() {
        val json = """
            {
              "version": "1",
              "executorFactory": "com.nonexistent.DoesNotExist",
              "nodes": [],
              "fields": []
            }
        """.trimIndent()

        val tempFile = java.io.File.createTempFile("unknown-factory", ".json").also {
            it.writeText(json)
            it.deleteOnExit()
        }

        val url = tempFile.toURI().toURL()
        val bootstrapper = ExecutionRegistryTenantAPIBootstrapper(injector, listOf(url))

        assertThrows<ClassNotFoundException> {
            runTest { bootstrapper.tenantModuleBootstrappers() }
        }
    }
}

/**
 * Minimal [ExecutorFactory] used in tests. Captures constructor args so tests can assert on them.
 */
class TestExecutorFactory(
    @Suppress("UNUSED_PARAMETER") injector: TenantCodeInjector,
    moduleName: String,
    @Suppress("UNUSED_PARAMETER") configUrl: URL,
) : ExecutorFactory {
    init {
        lastModuleName = moduleName
    }

    override fun createFieldResolverExecutor(
        configData: FieldEntry,
        schema: ViaductSchema
    ): FieldResolverExecutor {
        throw UnsupportedOperationException("not needed for bootstrapper tests")
    }

    override fun createNodeResolverExecutor(
        configData: NodeEntry,
        schema: ViaductSchema
    ): NodeResolverExecutor {
        throw UnsupportedOperationException("not needed for bootstrapper tests")
    }

    companion object {
        var lastModuleName: String? = null
    }
}
