package viaduct.engine.runtime

import graphql.execution.instrumentation.SimplePerformantInstrumentation
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.Coordinate
import viaduct.engine.api.Engine
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.spi.FieldSelectivityProvider
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.GlobalIDCodec
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.service.api.spi.mocks.MockFlagManager

class EngineExecutionContextImplTest {
    private val selectiveCoordinate = Coordinate("Query", "empty")
    private val otherCoordinate = Coordinate("Query", "other")

    @Test
    fun `copy keeps dataloaders request-scoped`() {
        val eec = ContextMocks().engineExecutionContextImpl
        val eecCopy = eec.copy()
        val resolver = mockk<NodeResolverExecutor> { every { typeName } returns "User" }
        val batchNodeLoader = eec.nodeDataLoader(resolver)
        val copyBatchNodeLoader = eecCopy.nodeDataLoader(resolver)
        assertSame(batchNodeLoader, copyBatchNodeLoader)
        assertSame(eec.engine, eecCopy.engine)
        assertTrue(eecCopy.fieldRssOriginFilteringKillSwitchEnabled)
    }

    @Test
    fun `field selectivity provider contributes to resolver selectivity when selective OER keys are enabled`() {
        val enabledContext =
            engineExecutionContext(
                flagManager = MockFlagManager.create(FlagManager.Flags.ENABLE_SELECTIVE_OER_KEYS)
            )
        val disabledContext = engineExecutionContext(flagManager = MockFlagManager.Disabled)

        assertTrue(enabledContext.isResolverSelective(selectiveCoordinate))
        assertFalse(enabledContext.isResolverSelective(otherCoordinate))
        assertFalse(disabledContext.isResolverSelective(selectiveCoordinate))
    }

    private fun engineExecutionContext(
        flagManager: FlagManager,
        fullSchema: ViaductSchema = ContextMocks().fullSchema,
        globalIDCodec: GlobalIDCodec = GlobalIDCodecDefault,
    ): EngineExecutionContextImpl {
        val factory =
            EngineExecutionContextFactory(
                fullSchema,
                DispatcherRegistry.Empty,
                SimplePerformantInstrumentation(),
                flagManager,
                mockk<Engine>(),
                globalIDCodec,
                meterRegistry = null,
                fieldSelectivityProvider = FieldSelectivityProvider { coordinate ->
                    coordinate == selectiveCoordinate
                },
            )

        return factory.create(fullSchema, requestContext = null) as EngineExecutionContextImpl
    }
}
