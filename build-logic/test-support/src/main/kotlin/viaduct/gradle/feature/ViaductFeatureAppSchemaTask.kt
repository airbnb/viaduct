package viaduct.gradle.feature

import org.gradle.api.tasks.TaskAction
import viaduct.gradle.common.ViaductSchemaTaskBase

/**
 * Task to generate schema objects for viaduct feature app tests.
 * This extends the common base class but is separate from ViaductSchemaTask
 * to avoid conflicts when both viaduct-schema and viaduct-feature-app plugins
 * are used in the same build.
 */
abstract class ViaductFeatureAppSchemaTask : ViaductSchemaTaskBase() {
    @TaskAction
    fun generateFeatureAppSchema() {
        executeSchemaGeneration()
    }
}
