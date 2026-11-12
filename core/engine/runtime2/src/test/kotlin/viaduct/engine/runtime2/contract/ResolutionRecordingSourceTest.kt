package viaduct.engine.runtime2.contract

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.recording.RecordedResolutionSource
import viaduct.engine.runtime2.resolution.recording.RecordedTest
import viaduct.engine.runtime2.resolution.recording.ResolutionRecording
import viaduct.engine.runtime2.resolution.recording.ResolutionRecordings

class ResolutionRecordingSourceTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `fixture recording retains resolver declarations and the original query`() {
        val schema = "extend type Query { value: Int @resolver(script: \"7\") }"
        val query = "query Example { __typename named: value }"
        val fixture = TestWorld.fromDSL(schema)
        val subject = Subject()
        val test = RecordedTest("Example", "source")
        ResolutionRecordings.begin(subject, test, directory)
        try {
            subject.resolveAndValidate(fixture, query)
            val recording = jacksonObjectMapper().readTree(ResolutionRecordings.outputFile(directory, test).toFile())
            assertEquals(schema, recording.path("source").path("schema").asText())
            assertEquals(query, recording.path("source").path("query").asText())
            assertEquals(RecordedResolutionSource(schema, query), jacksonObjectMapper().treeToValue(recording, ResolutionRecording::class.java).source)
        } finally {
            ResolutionRecordings.end(subject)
        }
    }

    @Test
    fun `observed fixture recording retains plain SDL and the query document`() {
        val schema = "type Query { value: Int }"
        val query = "query Example { ...Fields } fragment Fields on Query { named: value }"
        val fixture = TestWorld.fromSDL(schema)
        val subject = Subject()
        val test = RecordedTest("Example", "plain source")
        ResolutionRecordings.begin(subject, test, directory)
        try {
            subject.resolveAndValidateObserved(fixture, query)
            val recording = jacksonObjectMapper().readValue<ResolutionRecording>(ResolutionRecordings.outputFile(directory, test).toFile())
            assertEquals(RecordedResolutionSource(schema, query), recording.source)
        } finally {
            ResolutionRecordings.end(subject)
        }
    }

    private class Subject : ResolverContract {
        override fun resolve(
            operation: SharedOperationContext<*>,
            root: EngineObjectData.Sync,
            selections: SelectionForest
        ): ObjectEngineResult = ObjectEngineResult.of(operation.world.schema.requireQueryTypeDef())
    }
}
