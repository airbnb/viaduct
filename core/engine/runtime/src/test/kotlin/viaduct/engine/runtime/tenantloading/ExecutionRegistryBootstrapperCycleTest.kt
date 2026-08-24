package viaduct.engine.runtime.tenantloading

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockCheckerExecutorFactory
import viaduct.engine.api.mocks.MockExecutorCodeInjector
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.api.spi.FieldResolverExecutor

@ExperimentalCoroutinesApi
class ExecutionRegistryBootstrapperCycleTest {
    private val schema = MockSchema.mk(
        """
        extend type Query {
            foo: String
            bar: String
        }
        """.trimIndent()
    )

    private fun fieldExecutorWithObjectSelections(
        typeName: String,
        fieldName: String,
        selections: String,
    ): FieldResolverExecutor {
        val rss = RequiredSelectionSet(
            selections = SelectionsParser.parse(typeName, "fragment _ on $typeName { $selections }"),
            variablesResolvers = emptyList(),
            forChecker = false,
        )
        return object : FieldResolverExecutor {
            override val objectSelectionSet = rss
            override val querySelectionSet: RequiredSelectionSet? = null
            override val isSelective = false
            override val resolverId = "$typeName.$fieldName"
            override val metadata = ResolverMetadata.forMock("$typeName.$fieldName")
            override val isBatching = false

            override suspend fun batchResolve(
                selectors: List<FieldResolverExecutor.Selector>,
                context: EngineExecutionContext,
            ): Map<FieldResolverExecutor.Selector, Result<Any?>> = emptyMap()
        }
    }

    private val module = EngineTestModule(
        fullSchema = schema,
        fieldResolverExecutors = listOf(
            ("Query" to "foo") to fieldExecutorWithObjectSelections("Query", "foo", "bar"),
            ("Query" to "bar") to fieldExecutorWithObjectSelections("Query", "bar", "foo"),
        ),
    )

    @Test
    fun `cycle detection fires through file-based bootstrapping`() {
        assertThrows<RequiredSelectionsCycleException> {
            StandardDispatcherRegistryFactory(
                moduleConfigSources = listOf(module.toModuleConfigSource("test/cycle")),
                tenantModuleInjectorFactory = MockExecutorCodeInjector(module.mockExecutorRegistry),
                validator = ExecutorValidator(schema),
                checkerExecutorFactory = MockCheckerExecutorFactory(),
            ).create(schema)
        }
    }
}
