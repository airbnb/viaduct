package viaduct.gradle.classdiff

import org.gradle.api.tasks.TaskAction
import viaduct.gradle.common.ViaductSchemaTaskBase

/**
 * Task to generate schema objects for ClassDiff tests.
 *
 * This task extends the common ViaductSchemaTaskBase to generate schema objects
 * specifically for ClassDiff test files. It discovers GraphQL schemas from resource
 * files referenced in ClassDiff test classes and generates the corresponding
 * schema objects for testing.
 *
 * This is separate from ViaductSchemaTask to avoid conflicts when both
 * viaduct-schema and viaduct-classdiff plugins are used in the same build.
 */
abstract class ViaductClassDiffSchemaTask : ViaductSchemaTaskBase() {
    @TaskAction
    fun generateClassDiffSchema() {
        executeSchemaGeneration()
    }
}
