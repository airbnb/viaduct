@file:Suppress("DEPRECATION")

package viaduct.engine.runtime

import graphql.execution.instrumentation.SimplePerformantInstrumentation
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.Coordinate
import viaduct.engine.api.Engine
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.spi.FieldSelectivityProvider
import viaduct.engine.api.spi.MaterializedFieldValueReader
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.engine.runtime.mocks.createDispatcherRegistry
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.GlobalIDCodec
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.service.api.spi.mocks.MockFlagManager

class EngineExecutionContextImplTest {
    private val providerSelectiveCoordinate = Coordinate("Query", "empty")
    private val registrySelectiveCoordinate = Coordinate("Query", "registrySelective")
    private val nonSelectiveCoordinate = Coordinate("Query", "nonSelective")
    private val fullSchema =
        MockSchema.mk(
            """
                extend type Query {
                    empty: Int
                    registrySelective: Int
                    nonSelective: Int
                }
            """.trimIndent()
        )

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
    fun `combines field selectivity provider and registry metadata`() {
        val dispatcherRegistry =
            createDispatcherRegistry(
                fieldResolverExecutors =
                    mapOf(
                        registrySelectiveCoordinate to
                            MockFieldUnbatchedResolverExecutor(
                                isSelective = true,
                                resolverId = "registry-selective",
                            ),
                        nonSelectiveCoordinate to
                            MockFieldUnbatchedResolverExecutor(
                                isSelective = false,
                                resolverId = "non-selective",
                            ),
                    )
            )
        val context =
            engineExecutionContext(
                flagManager = MockFlagManager.Disabled,
                dispatcherRegistry = dispatcherRegistry,
            )

        assertTrue(context.isResolverSelective(providerSelectiveCoordinate))
        assertTrue(context.isResolverSelective(registrySelectiveCoordinate))
        assertFalse(context.isResolverSelective(nonSelectiveCoordinate))
    }

    @Test
    fun `mat resolution flag is latched per request`() {
        var enabled = true
        val flagManager = object : FlagManager {
            override fun isEnabled(flag: FlagManager.Flag): Boolean = flag == FlagManager.Flags.ENABLE_MAT_RESOLUTION && enabled
        }
        val enabledContext = engineExecutionContext(flagManager)

        enabled = false

        assertTrue(enabledContext.matResolutionEnabled)
        assertTrue(enabledContext.copy().matResolutionEnabled)
        assertFalse(engineExecutionContext(flagManager).matResolutionEnabled)
    }

    @Test
    fun `incremental execution flag is latched per request and preserved in copies and shadow executions`() {
        var enabled = true
        val flagManager = object : FlagManager {
            override fun isEnabled(flag: FlagManager.Flag): Boolean = flag == FlagManager.Flags.ENABLE_INCREMENTAL_EXECUTION && enabled
        }
        val enabledContext = engineExecutionContext(flagManager)

        enabled = false

        assertTrue(enabledContext.incrementalExecutionEnabled)
        assertTrue(enabledContext.copy().incrementalExecutionEnabled)
        assertTrue(enabledContext.forkForShadowExecution().incrementalExecutionEnabled)
        val disabledContext = engineExecutionContext(flagManager)
        assertFalse(disabledContext.incrementalExecutionEnabled)
        assertFalse(disabledContext.copy().incrementalExecutionEnabled)
        assertFalse(disabledContext.forkForShadowExecution().incrementalExecutionEnabled)
    }

    @Test
    fun `custom materialized reader is preserved in copies and shadow executions`() {
        val reader = MaterializedFieldValueReader { _, _, _ -> error("Unexpected field read") }
        val context = engineExecutionContext(MockFlagManager.Disabled, fieldValueReader = reader)

        assertSame(reader, context.materializedFieldValueReader)
        assertSame(reader, context.copy().materializedFieldValueReader)
        assertSame(reader, context.forkForShadowExecution().materializedFieldValueReader)
    }

    private fun engineExecutionContext(
        flagManager: FlagManager,
        fullSchema: EngineSchema = this.fullSchema,
        globalIDCodec: GlobalIDCodec = GlobalIDCodecDefault,
        dispatcherRegistry: DispatcherRegistry = DispatcherRegistry.Empty,
        fieldValueReader: MaterializedFieldValueReader = MaterializedFieldValueReader.Default,
    ): EngineExecutionContextImpl {
        val factory =
            EngineExecutionContextFactory(
                fullSchema,
                dispatcherRegistry,
                SimplePerformantInstrumentation(),
                flagManager,
                mockk<Engine>(),
                globalIDCodec,
                meterRegistry = null,
                materializedFieldValueReader = fieldValueReader,
                fieldSelectivityProvider = FieldSelectivityProvider { coordinate ->
                    coordinate == providerSelectiveCoordinate
                },
            )

        return factory.create(fullSchema, requestContext = null) as EngineExecutionContextImpl
    }
}
