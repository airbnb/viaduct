@file:Suppress("DEPRECATION")

package viaduct.service.runtime

import java.util.concurrent.Executors
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockExecutorCodeInjector
import viaduct.service.api.ExecutionInput
import viaduct.service.api.SchemaId

class ExecuteAsyncExecutorTest {
    @Test
    fun `executeAsync runs the operation on the supplied executor`() {
        val sdl = "extend type Query { result: String }"
        val module = EngineTestModule(sdl) {
            field("Query" to "result") {
                resolver {
                    fn { _, _, _, _, _ -> Thread.currentThread().name }
                }
            }
        }
        val subject = StandardViaduct.Builder()
            .withTenantModuleInjectorFactory(MockExecutorCodeInjector(module.mockExecutorRegistry))
            .withExecutorRegistryConfigSources(listOf(module.toModuleConfigSource()))
            .withSchemaConfiguration(SchemaConfiguration.fromSdl(sdl))
            .build()

        val executorThreadName = "viaduct-executor-e2e-thread"
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, executorThreadName) }
        try {
            val result = subject.executeAsync(ExecutionInput.create("{ result }"), SchemaId.Base, executor).join()

            assertTrue(result.errors.isEmpty(), "expected no errors, got ${result.errors}")
            val resolverThreadName = result.getData()?.get("result") as String?
            assertNotNull(resolverThreadName, "resolver did not run")
            // kotlinx-coroutines may append a coroutine name suffix (e.g. " @coroutine#2") to the
            // thread name while the resolver runs, so match the executor's thread name as a prefix.
            assertTrue(
                resolverThreadName!!.startsWith(executorThreadName),
                "expected resolver to run on the supplied executor (thread name prefix " +
                    "'$executorThreadName'), but ran on '$resolverThreadName'"
            )
        } finally {
            executor.shutdownNow()
        }
    }
}
