package viaduct.engine

import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import viaduct.engine.api.FullSchema
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.createSchemaWithWiring
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.FieldResolverDispatcherImpl
import viaduct.engine.runtime2.Engine2
import viaduct.service.api.spi.FlagManager

class EngineFactoryTest {
    @Test
    fun `engine2 flag selects the runtime2 engine`() {
        val schema = createSchemaWithWiring("extend type Query { value: String }")
        val config =
            EngineConfiguration.default.copy(
                flagManager =
                    object : FlagManager {
                        override fun isEnabled(flag: FlagManager.Flag): Boolean = flag == FlagManager.Flags.ENGINE2_ENABLED
                    },
            )
        val dispatcherRegistry =
            DispatcherRegistry.Impl(
                fieldResolverDispatchers =
                    mapOf(
                        ("Query" to "value") to
                            FieldResolverDispatcherImpl(
                                MockFieldUnbatchedResolverExecutor(resolverId = "Query.value") { _, _, _, _, _ -> "value" },
                            ),
                    ),
                nodeResolverDispatchers = emptyMap(),
                fieldCheckerDispatchers = emptyMap(),
                typeCheckerDispatchers = emptyMap(),
            )

        val engine =
            EngineFactory(config, dispatcherRegistry).create(
                schema = schema,
                fullSchema = FullSchema(schema),
            )

        assertInstanceOf(Engine2::class.java, engine)
    }
}
