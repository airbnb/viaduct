package viaduct.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.FullSchema
import viaduct.engine.api.mocks.createSchemaWithWiring
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.service.api.spi.FlagManager

class EngineFactoryTest {
    @Test
    fun `engine2 flag fails before the runtime2 integration is installed`() {
        val schema = createSchemaWithWiring("extend type Query { value: String }")
        val config =
            EngineConfiguration.default.copy(
                flagManager =
                    object : FlagManager {
                        override fun isEnabled(flag: FlagManager.Flag): Boolean = flag == FlagManager.Flags.ENGINE2_ENABLED
                    },
            )

        val failure =
            assertThrows<IllegalArgumentException> {
                EngineFactory(config, DispatcherRegistry.Empty).create(
                    schema = schema,
                    fullSchema = FullSchema(schema),
                )
            }

        assertEquals("ENGINE2_ENABLED requires the runtime2 engine integration", failure.message)
    }
}
