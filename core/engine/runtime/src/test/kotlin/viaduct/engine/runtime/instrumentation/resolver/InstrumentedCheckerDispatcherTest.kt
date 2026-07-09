@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.instrumentation.resolver

import graphql.schema.GraphQLObjectType
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.instrumentation.resolver.ResolverInstrumentationContext
import viaduct.engine.api.spi.CheckerExecutor
import viaduct.engine.runtime.CheckerDispatcher
import viaduct.engine.runtime.EngineObjectDataFactory

internal class InstrumentedCheckerDispatcherTest {
    private val testGraphQLObjectType = GraphQLObjectType.newObject().name("TestType").build()

    @Test
    fun `executor delegates to underlying dispatcher executor`() {
        val mockExecutor: CheckerExecutor = mockk()
        val dispatcher = object : CheckerDispatcher {
            override val requiredSelectionSets = emptyMap<String, viaduct.engine.api.RequiredSelectionSet?>()
            override val executor = mockExecutor

            override suspend fun execute(
                arguments: Map<String, Any?>,
                objectDataFactories: Map<String, EngineObjectDataFactory>,
                context: EngineExecutionContext,
                checkerType: CheckerExecutor.CheckerType
            ): CheckerResult = CheckerResult.Success
        }

        val testClass = InstrumentedCheckerDispatcher(dispatcher, RecordingResolverInstrumentation())

        assertSame(mockExecutor, testClass.executor)
    }

    @Test
    fun `execute materializes checker data with resolver instrumentation and wraps sync data for reads`() =
        runBlocking {
            val instrumentation = RecordingResolverInstrumentation()
            val syncData = mockk<EngineObjectData.Sync>()
            val expectedValue = "value"
            var capturedInstrumentationContext: ResolverInstrumentationContext? = null
            var capturedObjectData: EngineObjectData? = null
            val factory = EngineObjectDataFactory { instrumentationContext ->
                capturedInstrumentationContext = instrumentationContext
                syncData
            }
            val dispatcher = object : CheckerDispatcher {
                override val requiredSelectionSets = emptyMap<String, viaduct.engine.api.RequiredSelectionSet?>()
                override val executor: CheckerExecutor = mockk()

                override suspend fun execute(
                    arguments: Map<String, Any?>,
                    objectDataFactories: Map<String, EngineObjectDataFactory>,
                    context: EngineExecutionContext,
                    checkerType: CheckerExecutor.CheckerType
                ): CheckerResult {
                    capturedObjectData = objectDataFactories.getValue("checker").create(null)
                    capturedObjectData!!.fetch("field")
                    return CheckerResult.Success
                }
            }

            every { syncData.type } returns testGraphQLObjectType
            every { syncData.get("field") } returns expectedValue

            val testClass = InstrumentedCheckerDispatcher(dispatcher, instrumentation)
            val result = testClass.execute(
                arguments = emptyMap(),
                objectDataFactories = mapOf("checker" to factory),
                context = mockk(),
                checkerType = CheckerExecutor.CheckerType.FIELD,
            )

            assertSame(CheckerResult.Success, result)
            assertNotNull(capturedInstrumentationContext)
            capturedObjectData.shouldBeInstanceOf<InstrumentedEngineObjectData.Sync>()
            assertEquals(1, instrumentation.syncFetchSelectionContexts.size)
            val readContext = instrumentation.syncFetchSelectionContexts.first()
            assertEquals("field", readContext.parameters.selection)
            assertEquals(expectedValue, readContext.result)
        }
}
