package viaduct.engine.runtime2.resolution.recording.junit

import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import viaduct.engine.runtime2.resolution.recording.RecordedTest
import viaduct.engine.runtime2.resolution.recording.ResolutionRecordings

class ResolutionRecordingExtension : BeforeEachCallback, AfterEachCallback {
    override fun beforeEach(context: ExtensionContext) {
        val directory = System.getProperty(ResolutionRecordings.DIRECTORY_PROPERTY) ?: return
        if (!context.requiredTestMethod.isAnnotationPresent(Test::class.java)) return
        ResolutionRecordings.begin(
            context.requiredTestInstance,
            RecordedTest(context.requiredTestClass.name, context.requiredTestMethod.name),
            Path.of(directory),
        )
    }

    override fun afterEach(context: ExtensionContext) {
        ResolutionRecordings.end(context.requiredTestInstance)
    }
}
