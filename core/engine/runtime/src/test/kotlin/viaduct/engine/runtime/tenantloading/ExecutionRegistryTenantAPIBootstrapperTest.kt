package viaduct.engine.runtime.tenantloading

import java.net.URL
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.bootstrap.executionregistry.FieldEntryConfig
import viaduct.engine.api.bootstrap.executionregistry.NodeEntryConfig
import viaduct.engine.api.spi.ExecutorFactory
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.service.api.spi.CodeInjector
import viaduct.service.api.spi.SharedTenantModuleBootstrapper
import viaduct.service.api.spi.TenantModuleBootstrapper

class ExecutionRegistryTenantAPIBootstrapperTest {
    private val injector = CodeInjector.Naive

    @Test
    fun `empty URL list returns empty iterable`() =
        runTest {
            val bootstrapper = ExecutionRegistryTenantAPIBootstrapper(
                registryUrls = emptyList(),
                tenantModuleBootstrapper = RecordingTenantModuleBootstrapper(),
            )
            assertEquals(0, bootstrapper.tenantModuleBootstrappers().count())
        }

    @Test
    fun `valid registry URL creates module bootstrapper with grtPackagePrefix from JSON`() =
        runTest {
            val url = requireNotNull(
                Thread.currentThread().contextClassLoader
                    .getResource("META-INF/viaduct/modules/com.example.test.json")
            ) { "Test resource not found on classpath" }

            val bootstrapper = ExecutionRegistryTenantAPIBootstrapper(
                registryUrls = listOf(url),
                tenantModuleBootstrapper = SharedTenantModuleBootstrapper(injector),
            )
            assertEquals(1, bootstrapper.tenantModuleBootstrappers().toList().size)
            assertEquals("viaduct.api.grts", TestExecutorFactory.lastGrtPackagePrefix)
        }

    @Test
    fun `tenantModuleBootstrapper is called with tenant name and null bootstrap class when no bootstrapClass in registry`() =
        runTest {
            val url = requireNotNull(
                Thread.currentThread().contextClassLoader
                    .getResource("META-INF/viaduct/modules/com.example.test.json")
            ) { "Test resource not found on classpath" }

            val recordingBootstrapper = RecordingTenantModuleBootstrapper()
            val bootstrapper = ExecutionRegistryTenantAPIBootstrapper(
                registryUrls = listOf(url),
                tenantModuleBootstrapper = recordingBootstrapper,
            )
            bootstrapper.tenantModuleBootstrappers()

            assertEquals(listOf("com.example.test" to null), recordingBootstrapper.calls)
        }

    @Test
    fun `tenantModuleBootstrapper is called with tenant name and loaded bootstrap class when bootstrapClass present in registry`() =
        runTest {
            val url = requireNotNull(
                Thread.currentThread().contextClassLoader
                    .getResource("META-INF/viaduct/modules/com.example.bootstrapped.json")
            ) { "Test resource not found on classpath" }

            val recordingBootstrapper = RecordingTenantModuleBootstrapper()
            val bootstrapper = ExecutionRegistryTenantAPIBootstrapper(
                registryUrls = listOf(url),
                tenantModuleBootstrapper = recordingBootstrapper,
            )
            bootstrapper.tenantModuleBootstrappers()

            assertEquals(
                listOf("com.example.bootstrapped" to TestBootstrapClass::class.java),
                recordingBootstrapper.calls,
            )
        }

    @Test
    fun `unknown executorFactory FQN in JSON throws ClassNotFoundException`() {
        val json = """
            {
              "version": "1",
              "executorFactory": "com.nonexistent.DoesNotExist",
              "grtPackagePrefix": "viaduct.api.grts",
              "nodes": [],
              "fields": []
            }
        """.trimIndent()

        val tempFile = java.io.File.createTempFile("unknown-factory", ".json").also {
            it.writeText(json)
            it.deleteOnExit()
        }

        val url = tempFile.toURI().toURL()
        val bootstrapper = ExecutionRegistryTenantAPIBootstrapper(
            registryUrls = listOf(url),
            tenantModuleBootstrapper = SharedTenantModuleBootstrapper(injector),
        )

        assertThrows<ClassNotFoundException> {
            runTest { bootstrapper.tenantModuleBootstrappers() }
        }
    }

    @Test
    fun `finalize is called after all bootstrap calls and before executor factory construction`() =
        runTest {
            val urlWithoutBootstrapClass = requireNotNull(
                Thread.currentThread().contextClassLoader
                    .getResource("META-INF/viaduct/modules/com.example.test.json")
            ) { "Test resource not found on classpath" }
            val urlWithBootstrapClass = requireNotNull(
                Thread.currentThread().contextClassLoader
                    .getResource("META-INF/viaduct/modules/com.example.bootstrapped.json")
            ) { "Test resource not found on classpath" }

            val recordingBootstrapper = FinalizingTenantModuleBootstrapper()
            TestExecutorFactory.constructorEvents.clear()

            val bootstrapper = ExecutionRegistryTenantAPIBootstrapper(
                registryUrls = listOf(urlWithoutBootstrapClass, urlWithBootstrapClass),
                tenantModuleBootstrapper = recordingBootstrapper,
            )

            bootstrapper.tenantModuleBootstrappers()

            assertEquals(
                listOf(
                    "bootstrap:com.example.test",
                    "bootstrap:com.example.bootstrapped",
                    "finalize",
                ),
                recordingBootstrapper.events,
            )
            assertEquals(
                listOf(
                    "constructor:finalized=true",
                    "constructor:finalized=true",
                ),
                TestExecutorFactory.constructorEvents,
            )
        }

    @Test
    fun `finalize is not called when bootstrap fails`() {
        val url = requireNotNull(
            Thread.currentThread().contextClassLoader
                .getResource("META-INF/viaduct/modules/com.example.test.json")
        ) { "Test resource not found on classpath" }

        val throwingBootstrapper = ThrowingTenantModuleBootstrapper()
        val bootstrapper = ExecutionRegistryTenantAPIBootstrapper(
            registryUrls = listOf(url),
            tenantModuleBootstrapper = throwingBootstrapper,
        )

        assertThrows<IllegalStateException> {
            runTest { bootstrapper.tenantModuleBootstrappers() }
        }

        assertEquals(1, throwingBootstrapper.bootstrapCalls)
        assertEquals(0, throwingBootstrapper.finalizeCalls)
    }
}

/**
 * Minimal [ExecutorFactory] used in tests. Captures constructor args so tests can assert on them.
 */
class TestExecutorFactory(
    @Suppress("UNUSED_PARAMETER") injector: CodeInjector,
    grtPackagePrefix: String,
    @Suppress("UNUSED_PARAMETER") configUrl: URL,
) : ExecutorFactory {
    init {
        lastGrtPackagePrefix = grtPackagePrefix
        val finalizingInjector = injector as? FinalizingCodeInjector
        if (finalizingInjector != null) {
            constructorEvents.add("constructor:finalized=${finalizingInjector.finalized}")
        }
    }

    override fun createFieldResolverExecutor(
        configData: FieldEntryConfig,
        schema: ViaductSchema
    ): FieldResolverExecutor {
        throw UnsupportedOperationException("not needed for bootstrapper tests")
    }

    override fun createNodeResolverExecutor(
        configData: NodeEntryConfig,
        schema: ViaductSchema
    ): NodeResolverExecutor {
        throw UnsupportedOperationException("not needed for bootstrapper tests")
    }

    companion object {
        var lastGrtPackagePrefix: String? = null
        val constructorEvents = mutableListOf<String>()
    }
}

class RecordingTenantModuleBootstrapper : TenantModuleBootstrapper {
    val calls = mutableListOf<Pair<String, Class<*>?>>()

    override suspend fun bootstrap(
        tenantName: String,
        tenantBootstrapClass: Class<*>?
    ): CodeInjector {
        calls.add(tenantName to tenantBootstrapClass)
        return CodeInjector.Naive
    }
}

private class FinalizingCodeInjector : CodeInjector {
    var finalized: Boolean = false

    override fun <T> getProvider(clazz: Class<T>) = throw UnsupportedOperationException("not needed for bootstrapper tests")
}

private class FinalizingTenantModuleBootstrapper : TenantModuleBootstrapper {
    val events = mutableListOf<String>()
    private val injectors = mutableListOf<FinalizingCodeInjector>()

    override suspend fun bootstrap(
        tenantName: String,
        tenantBootstrapClass: Class<*>?,
    ): CodeInjector {
        events.add("bootstrap:$tenantName")
        return FinalizingCodeInjector().also(injectors::add)
    }

    override suspend fun finalize() {
        events.add("finalize")
        injectors.forEach { it.finalized = true }
    }
}

private class ThrowingTenantModuleBootstrapper : TenantModuleBootstrapper {
    var bootstrapCalls: Int = 0
    var finalizeCalls: Int = 0

    override suspend fun bootstrap(
        tenantName: String,
        tenantBootstrapClass: Class<*>?,
    ): CodeInjector {
        bootstrapCalls += 1
        throw IllegalStateException("boom")
    }

    override suspend fun finalize() {
        finalizeCalls += 1
    }
}

/** Marker class used as the bootstrapClass in com.example.bootstrapped.json test fixture. */
class TestBootstrapClass
