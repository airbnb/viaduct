@file:Suppress("DEPRECATION", "ForbiddenImport")

package viaduct.service.runtime

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockExecutorCodeInjector
import viaduct.graphql.test.assertJson
import viaduct.service.api.ExecutionInput
import viaduct.service.api.SchemaId

class CoroutineContextPropagationTest {
    data class TestContext(val bar: Int?) : CoroutineContext.Element {
        companion object Key : CoroutineContext.Key<TestContext>

        override val key: CoroutineContext.Key<*> = Key
    }

    @Test
    fun `coroutine context is propagated to resolver functions`() {
        val sdl = "extend type Query { result: Int }"
        val module = EngineTestModule(sdl) {
            field("Query" to "result") {
                resolver {
                    fn { _, _, _, _, _ ->
                        coroutineContext[TestContext]?.bar
                    }
                }
            }
        }
        val subject = StandardViaduct.Builder()
            .withTenantModuleInjectorFactory(MockExecutorCodeInjector(module.mockExecutorRegistry))
            .withExecutorRegistryConfigSources(listOf(module.toModuleConfigSource()))
            .withSchemaConfiguration(SchemaConfiguration.fromSdl(sdl))
            .build()
        runBlocking {
            withContext(TestContext(42)) {
                val input = ExecutionInput.create("{result}")
                val result = subject.execute(input, SchemaId.Base)
                result.assertJson("{data: {result: 42}}")
            }
        }
    }
}
