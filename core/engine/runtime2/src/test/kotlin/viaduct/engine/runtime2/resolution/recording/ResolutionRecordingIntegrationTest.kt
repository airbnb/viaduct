package viaduct.engine.runtime2.resolution.recording

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.contract.ResolverContract
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.resolution.ResolutionDispatcherResource
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.schema.operationSelectionsFrom

class ResolutionRecordingIntegrationTest : ResolverContract, ResolutionDispatcherResource {
    private val world = TestWorld.fromDSL("extend type Query { value: Int! @resolver(result: 7) }")

    @Test
    fun `JUnit associates a recording with the concrete test class and method`() {
        val observer = CorrectnessResolverObserver()
        val observation = observeResolution(world.assumptions, world.schema.objectOf("Query"), world.schemas.operationSelectionsFrom("{ value }"), observer)
        assertEquals(7, observation.result.getCell(observation.result.keys.single()).value.get())
        assertEquals(1, observer.invokedResolverOccurrences().size)
        val effective = assertIs<CorrectnessResolverObserver>(observation.operation.resolutionObserver)
        assertEquals(observer.invokedResolverOccurrences(), effective.invokedResolverOccurrences())

        output("JUnit associates a recording with the concrete test class and method")?.let { output ->
            val recording = jacksonObjectMapper().readValue<ResolutionRecording>(output.toFile())
            assertEquals(RecordedTest(javaClass.name, "JUnit associates a recording with the concrete test class and method"), recording.test)
            assertTrue(recording.completed)
            assertEquals(2, recording.tasks.size)
        }
    }

    @Test
    fun `explicit opt out permits repeated resolution with validation observers`() {
        repeat(2) {
            val observer = CorrectnessResolverObserver()
            observeResolution(world.assumptions, world.schema.objectOf("Query"), world.schemas.operationSelectionsFrom("{ value }"), observer, record = false)
            assertEquals(1, observer.invokedResolverOccurrences().size)
        }
        output("explicit opt out permits repeated resolution with validation observers")?.let { assertFalse(Files.exists(it)) }
    }

    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest
    ): ObjectEngineResult = operation.resolveWithTestDispatcher(selections)

    private fun output(method: String): Path? =
        System.getProperty(ResolutionRecordings.DIRECTORY_PROPERTY)?.let {
            ResolutionRecordings.outputFile(Path.of(it), RecordedTest(javaClass.name, method))
        }
}
