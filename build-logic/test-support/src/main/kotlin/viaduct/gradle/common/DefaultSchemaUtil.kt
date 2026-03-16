package viaduct.gradle.common

import java.io.File
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.ProjectLayout
import org.gradle.api.logging.Logger
import viaduct.gradle.defaultschema.DefaultSchemaPlugin

/**
 * Utility for handling default schema integration in Viaduct tasks.
 * Provides common functionality for including the default_schema.graphqls file
 * from the plugins resources in schema generation operations.
 */
object DefaultSchemaUtil {
    /**
     * Gets all schema files including the default schema from the plugins resources
     */
    fun getSchemaFilesIncludingDefault(
        schemaFiles: ConfigurableFileCollection,
        projectLayout: ProjectLayout,
        logger: Logger
    ): Set<File> {
        val allSchemaFiles = mutableSetOf<File>()

        // Add configured schema files
        allSchemaFiles.addAll(schemaFiles.files)

        // Find and add the default schema from the generated build artifact
        try {
            val defaultSchemaFile = findDefaultSchemaFile(projectLayout, logger)
            allSchemaFiles.add(defaultSchemaFile)
            logger.info("Including default schema from: ${defaultSchemaFile.absolutePath}")
        } catch (e: IllegalStateException) {
            logger.warn("Default schema not found: ${e.message}")
        }

        return allSchemaFiles
    }

    /**
     * Finds the default_schema.graphqls file from the generated build artifact.
     * Relies on DefaultSchemaOutputTask to have extracted the schema to a well-known location.
     */
    private fun findDefaultSchemaFile(
        projectLayout: ProjectLayout,
        logger: Logger
    ): File {
        val generatedSchemaFile = projectLayout.buildDirectory
            .file("${DefaultSchemaPlugin.DEFAULT_SCHEMA_BUILD_PATH}/${DefaultSchemaPlugin.DEFAULT_SCHEMA_FILENAME}")
            .get()
            .asFile

        if (!generatedSchemaFile.exists()) {
            throw IllegalStateException(
                "Default schema not found at expected location: ${generatedSchemaFile.absolutePath}. " +
                    "Ensure DefaultSchemaPlugin is applied and schema generation tasks depend on processResources."
            )
        }

        logger.debug("Using generated default schema: ${generatedSchemaFile.absolutePath}")
        return generatedSchemaFile
    }
}
