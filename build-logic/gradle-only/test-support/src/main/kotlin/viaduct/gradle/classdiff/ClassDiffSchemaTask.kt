package viaduct.gradle.classdiff

import org.gradle.api.tasks.TaskAction
import viaduct.gradle.common.SchemaTaskBase

/**
 * Task to generate schema objects for ClassDiff tests.
 *
 * Separate from ViaductSchemaTask to avoid conflicts when both
 * viaduct-schema and viaduct-classdiff plugins are used in the same build.
 */
abstract class ClassDiffSchemaTask : SchemaTaskBase() {
    @TaskAction
    fun generateClassDiffSchema() {
        executeSchemaGeneration()
    }
}
