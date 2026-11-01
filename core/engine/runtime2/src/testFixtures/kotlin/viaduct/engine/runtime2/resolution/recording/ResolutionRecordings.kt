package viaduct.engine.runtime2.resolution.recording

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.IdentityHashMap
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.resolution.framework.ResolutionObserver

/** JUnit supplies the test identity; resolution may claim it from any coroutine thread. */
object ResolutionRecordings {
    const val DIRECTORY_PROPERTY = "viaduct.runtime2.resolutionRecording.directory"
    private val sessions = IdentityHashMap<Any, TestSession>()

    @Synchronized
    fun begin(
        testInstance: Any,
        test: RecordedTest,
        directory: Path
    ) {
        check(testInstance !in sessions) { "Recording context already registered for $test" }
        val output = outputFile(directory, test)
        Files.deleteIfExists(output)
        sessions[testInstance] = TestSession(test, output)
    }

    @Synchronized
    fun end(testInstance: Any) {
        sessions.remove(testInstance)
    }

    @Synchronized
    fun setSource(
        testInstance: Any,
        source: RecordedResolutionSource
    ) {
        val session = sessions[testInstance] ?: return
        check(!session.claimed) { "Source must be supplied before recording starts" }
        session.source = source
    }

    @Synchronized
    fun start(
        testInstance: Any,
        delegate: ResolutionObserver,
        initialDemand: SelectionForest? = null,
    ): ResolutionRecordingSession? {
        val session = sessions[testInstance] ?: return null
        check(!session.claimed) {
            "Multiple resolutions in ${session.test}; use record = false for tests without one resolution per method"
        }
        session.claimed = true
        return ResolutionRecordingSession(RecordingResolutionObserver(session.test, delegate, initialDemand, session.source), session.output)
    }

    fun outputFile(
        directory: Path,
        test: RecordedTest
    ): Path {
        val readableName = test.methodName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        val digest = MessageDigest.getInstance("SHA-256").digest(test.methodName.toByteArray(UTF_8))
            .take(8).joinToString("") { "%02x".format(it) }
        return directory.resolve(test.className).resolve("$readableName-$digest.json")
    }

    private class TestSession(val test: RecordedTest, val output: Path, var claimed: Boolean = false) {
        var source: RecordedResolutionSource? = null
    }
}

class ResolutionRecordingSession internal constructor(
    val observer: RecordingResolutionObserver,
    private val output: Path,
) {
    fun resolve(resolve: () -> ObjectEngineResult): ObjectEngineResult {
        val result = try {
            resolve()
        } catch (cause: Exception) {
            observer.failed(cause)
            try {
                write()
            } catch (writeFailure: Exception) {
                cause.addSuppressed(writeFailure)
            }
            throw cause
        }
        observer.complete(result)
        write()
        return result
    }

    private fun write() {
        Files.createDirectories(output.parent)
        val temporary = Files.createTempFile(output.parent, ".recording-", ".json")
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), observer.snapshot())
            try {
                Files.move(temporary, output, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, output, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    companion object {
        private val mapper = jacksonObjectMapper()
    }
}
