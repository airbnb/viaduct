package viaduct.gradle.defaultschema

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Task that extracts the default_schema.graphqls file from plugin resources
 * to a predictable build location during the build process.
 *
 * This ensures that:
 * 1. Tests can find the default schema at a known classpath location
 * 2. Generation tasks have a consistent file path to work with
 * 3. The schema file is available as a proper build artifact
 */
abstract class DefaultSchemaOutputTask : DefaultTask() {
    @get:OutputFile
    abstract val defaultSchemaFile: RegularFileProperty

    init {
        group = "viaduct-schema"
        description = "Extracts default_schema.graphqls from plugin resources to build directory"
    }

    @TaskAction
    fun extractDefaultSchema() {
        val outputFile = defaultSchemaFile.get().asFile

        // Ensure parent directory exists
        outputFile.parentFile.mkdirs()

        // Extract from plugin resources
        val resourceStream = DefaultSchemaOutputTask::class.java.getResourceAsStream("/default_schema.graphqls")
        if (resourceStream != null) {
            resourceStream.use { stream ->
                outputFile.writeText(stream.bufferedReader().readText())
            }
            logger.info("Extracted default schema to: ${outputFile.absolutePath}")
        } else {
            // Fallback: look in the source tree (useful for development)
            val possiblePaths = listOf(
                "projects/viaduct/oss/plugins/src/main/resources/default_schema.graphqls",
                "../plugins/src/main/resources/default_schema.graphqls",
                "src/main/resources/default_schema.graphqls"
            )

            var found = false
            for (path in possiblePaths) {
                val sourceFile = File(project.rootDir, path)
                if (sourceFile.exists()) {
                    outputFile.writeText(sourceFile.readText())
                    logger.info("Extracted default schema from source tree: ${sourceFile.absolutePath}")
                    found = true
                    break
                }
            }

            if (!found) {
                throw RuntimeException("Default schema not found in plugin resources or source tree")
            }
        }
    }
}
