package viaduct.engine.runtime2.resolution.recording

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.contract.ResolverContract
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.resolution.framework.ResolutionObserver
import viaduct.engine.runtime2.resolution.framework.ResolutionTask
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.schema.operationSelectionsFrom

class ResolutionRecordingsTest {
    @TempDir
    lateinit var directory: Path

    private val world = TestWorld.fromSDL("type Query { value: Int }")
    private val root = engineObjectDataOf(world.schema.requireQueryTypeDef())
    private val selections = world.schemas.operationSelectionsFrom("{ value }")

    @Test
    fun `no context and explicit opt out preserve the supplied observer without recording`() {
        val subject = Subject()
        var prepared = 0
        val observer = CorrectnessResolverObserver(object : ResolutionObserver {
            override val onTaskPrepared: (ResolutionTask) -> Unit = { prepared++ }
        })
        val withoutContext = subject.observeResolution(world.assumptions, root, selections, observer)
        assertSame(observer, withoutContext.operation.resolutionObserver)
        val test = RecordedTest("Example", "opt out")
        ResolutionRecordings.begin(subject, test, directory)
        try {
            repeat(2) {
                val observation = subject.observeResolution(world.assumptions, root, selections, observer, record = false)
                assertSame(observer, observation.operation.resolutionObserver)
            }
            assertFalse(Files.exists(ResolutionRecordings.outputFile(directory, test)))
            assertEquals(3, prepared)
        } finally {
            ResolutionRecordings.end(subject)
        }
    }

    @Test
    fun `recording is associated with test identity and a rebuild replaces the artifact`() {
        val subject = Subject()
        val test = RecordedTest("Example", "spaces / punctuation ü")
        val output = ResolutionRecordings.outputFile(directory, test)
        repeat(2) {
            ResolutionRecordings.begin(subject, test, directory)
            try {
                assertFalse(Files.exists(output))
                val observation = subject.observeResolution(world.assumptions, root, selections)
                assertIs<CorrectnessResolverObserver>(observation.operation.resolutionObserver)
                val recording = jacksonObjectMapper().readValue<ResolutionRecording>(output.toFile())
                assertEquals(test, recording.test)
                assertTrue(recording.completed)
                assertEquals(1, recording.tasks.size)
                assertEquals(2, recording.events.size)
                assertEquals("Query.value", recording.initialDemand!!.single()["key"]["field"].asText())
                assertNull(recording.source)
                assertFailsWith<IllegalStateException> {
                    subject.observeResolution(world.assumptions, root, selections)
                }
            } finally {
                ResolutionRecordings.end(subject)
            }
        }
        assertEquals(directory.resolve("Example"), output.parent)
        assertEquals(1L, Files.list(output.parent).use { it.count() })
        assertTrue(output.fileName.toString().endsWith(".json"))
        assertFalse(output.fileName.toString().contains('/'))
        assertNotSameFilename(test.copy(methodName = "spaces _ punctuation ü"), test)
        assertNotSameFilename(test.copy(className = "Other"), test)
        assertNull(ResolutionRecordings.start(subject, ResolutionObserver.NOP))
    }

    @Test
    fun `failed resolution writes partial evidence and preserves the original exception`() {
        val failure = IllegalStateException("resolution failed")
        val subject = Subject(failure)
        val test = RecordedTest("Example", "failure")
        ResolutionRecordings.begin(subject, test, directory)
        try {
            assertSame(failure, assertFailsWith<IllegalStateException> { subject.observeResolution(world.assumptions, root, selections) })
            val recording = jacksonObjectMapper().readValue<ResolutionRecording>(ResolutionRecordings.outputFile(directory, test).toFile())
            assertFalse(recording.completed)
            assertEquals(RecordedFailure(failure.javaClass.name, "resolution failed"), recording.failure)
            assertEquals(1, recording.events.size)
        } finally {
            ResolutionRecordings.end(subject)
        }
    }

    @Test
    fun `write failure is suppressed onto resolution failure`() {
        val failure = IllegalStateException("resolution failed")
        val subject = Subject(failure)
        val test = RecordedTest("Example", "failure")
        ResolutionRecordings.begin(subject, test, directory)
        try {
            Files.writeString(directory.resolve(test.className), "blocking file")
            assertSame(failure, assertFailsWith<IllegalStateException> { subject.observeResolution(world.assumptions, root, selections) })
            assertEquals(1, failure.suppressed.size)
        } finally {
            ResolutionRecordings.end(subject)
        }
    }

    private fun assertNotSameFilename(
        first: RecordedTest,
        second: RecordedTest
    ) {
        assertTrue(ResolutionRecordings.outputFile(directory, first) != ResolutionRecordings.outputFile(directory, second))
    }

    private class Subject(private val failure: Exception? = null) : ResolverContract {
        override fun resolve(
            operation: SharedOperationContext<*>,
            root: EngineObjectData.Sync,
            selections: SelectionForest
        ): ObjectEngineResult {
            operation.resolutionObserver.onTaskPrepared?.invoke(object : ResolutionTask {
                override val creator: ResolutionTask? = null
            })
            if (failure != null) throw failure
            return ObjectEngineResult.of(operation.world.schema.requireQueryTypeDef())
        }
    }
}
