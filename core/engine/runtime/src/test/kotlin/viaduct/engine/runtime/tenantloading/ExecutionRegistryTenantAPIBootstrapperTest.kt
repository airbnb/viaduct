package viaduct.engine.runtime.tenantloading

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
import viaduct.service.api.spi.InputStreamSource
import viaduct.service.api.spi.SharedTenantModuleInjectorFactory
import viaduct.service.api.spi.TenantModuleInjectorFactory

class ExecutionRegistryTenantAPIBootstrapperTest {
    private val injector = CodeInjector.Naive

    @Test
    fun `empty config source list returns empty iterable`() =
        runTest {
            val bootstrapper = ExecutionRegistryTenantAPIBootstrapper(
                tenantModuleInjectorFactory = RecordingTenantModuleInjectorFactory(),
                executorRegistryConfigSources = emptyList(),
            )
            assertEquals(0, bootstrapper.tenantModuleBootstrappers().count())
        }

    @Test
    fun `valid registry source creates one module bootstrapper`() =
        runTest {
            val url = requireNotNull(
                Thread.currentThread().contextClassLoader
                    .getResource("META-INF/viaduct/modules/com.example.test.json")
            ) { "Test resource not found on classpath" }

            val bootstrapper = ExecutionRegistryTenantAPIBootstrapper(
                tenantModuleInjectorFactory = SharedTenantModuleInjectorFactory(injector),
                executorRegistryConfigSources = listOf(InputStreamSource.fromUrl(url)),
            )
            assertEquals(1, bootstrapper.tenantModuleBootstrappers().toList().size)
        }

    @Test
    fun `string registry source creates one module bootstrapper`() =
        runTest {
            val bootstrapper = ExecutionRegistryTenantAPIBootstrapper(
                tenantModuleInjectorFactory = SharedTenantModuleInjectorFactory(injector),
                executorRegistryConfigSources = listOf(
                    InputStreamSource.fromString(
                        """
                            {
                              "version": "1",
                              "tenantName": "inline",
                              "executorFactory": "viaduct.engine.runtime.tenantloading.TestExecutorFactory",
                              "nodes": [],
                              "fields": []
                            }
                        """.trimIndent(),
                        name = "com.example.inline",
                    )
                ),
            )

            assertEquals(1, bootstrapper.tenantModuleBootstrappers().toList().size)
        }

    @Test
    fun `tenantModuleInjectorFactory is called with tenant name and null bootstrap class when no bootstrapClass in registry`() =
        runTest {
            val url = requireNotNull(
                Thread.currentThread().contextClassLoader
                    .getResource("META-INF/viaduct/modules/com.example.test.json")
            ) { "Test resource not found on classpath" }

            val recordingBootstrapper = RecordingTenantModuleInjectorFactory()
            val bootstrapper = ExecutionRegistryTenantAPIBootstrapper(
                tenantModuleInjectorFactory = recordingBootstrapper,
                executorRegistryConfigSources = listOf(InputStreamSource.fromUrl(url)),
            )
            bootstrapper.tenantModuleBootstrappers()

            assertEquals(listOf("test" to null), recordingBootstrapper.calls)
        }

    @Test
    fun `tenantModuleInjectorFactory is called with tenant name and loaded bootstrap class when bootstrapClass present in registry`() =
        runTest {
            val url = requireNotNull(
                Thread.currentThread().contextClassLoader
                    .getResource("META-INF/viaduct/modules/com.example.bootstrapped.json")
            ) { "Test resource not found on classpath" }

            val recordingBootstrapper = RecordingTenantModuleInjectorFactory()
            val bootstrapper = ExecutionRegistryTenantAPIBootstrapper(
                tenantModuleInjectorFactory = recordingBootstrapper,
                executorRegistryConfigSources = listOf(InputStreamSource.fromUrl(url)),
            )
            bootstrapper.tenantModuleBootstrappers()

            assertEquals(
                listOf("bootstrapped" to TestBootstrapClass::class.java),
                recordingBootstrapper.calls,
            )
        }

    @Test
    fun `unknown executorFactory FQN in JSON throws ClassNotFoundException`() {
        val json = """
            {
              "version": "1",
              "tenantName": "unknown",
              "executorFactory": "com.nonexistent.DoesNotExist",
              "nodes": [],
              "fields": []
            }
        """.trimIndent()

        val tempFile = java.io.File.createTempFile("unknown-factory", ".json").also {
            it.writeText(json)
            it.deleteOnExit()
        }

        val bootstrapper = ExecutionRegistryTenantAPIBootstrapper(
            tenantModuleInjectorFactory = SharedTenantModuleInjectorFactory(injector),
            executorRegistryConfigSources = listOf(InputStreamSource.fromFile(tempFile)),
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

            val recordingBootstrapper = FinalizingTenantModuleInjectorFactory()
            TestExecutorFactory.constructorEvents.clear()

            val bootstrapper = ExecutionRegistryTenantAPIBootstrapper(
                tenantModuleInjectorFactory = recordingBootstrapper,
                executorRegistryConfigSources = listOf(
                    InputStreamSource.fromUrl(urlWithoutBootstrapClass),
                    InputStreamSource.fromUrl(urlWithBootstrapClass),
                ),
            )

            bootstrapper.tenantModuleBootstrappers()

            assertEquals(
                listOf(
                    "bootstrap:test",
                    "bootstrap:bootstrapped",
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

        val throwingBootstrapper = ThrowingTenantModuleInjectorFactory()
        val bootstrapper = ExecutionRegistryTenantAPIBootstrapper(
            tenantModuleInjectorFactory = throwingBootstrapper,
            executorRegistryConfigSources = listOf(InputStreamSource.fromUrl(url)),
        )

        assertThrows<IllegalStateException> {
            runTest { bootstrapper.tenantModuleBootstrappers() }
        }

        assertEquals(1, throwingBootstrapper.bootstrapCalls)
        assertEquals(0, throwingBootstrapper.finalizeCalls)
    }
}

/**
 * Minimal [ExecutorFactory] used in tests.
 */
class TestExecutorFactory(
    injector: CodeInjector,
    @Suppress("UNUSED_PARAMETER") configSource: InputStreamSource,
) : ExecutorFactory {
    init {
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
        val constructorEvents = mutableListOf<String>()
    }
}

class RecordingTenantModuleInjectorFactory : TenantModuleInjectorFactory {
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

private class FinalizingTenantModuleInjectorFactory : TenantModuleInjectorFactory {
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

private class ThrowingTenantModuleInjectorFactory : TenantModuleInjectorFactory {
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
