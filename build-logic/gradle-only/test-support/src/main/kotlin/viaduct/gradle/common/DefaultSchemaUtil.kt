package viaduct.gradle.common

import java.io.File
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.logging.Logger

/**
 * Utility for handling default schema integration in Viaduct tasks.
 */
object DefaultSchemaUtil {
    /**
     * Returns the set of schema files to pass to codegen, including [defaultSchemaFile].
     * Callers must declare [defaultSchemaFile] as an [org.gradle.api.tasks.InputFile] on
     * the enclosing task so that its content is included in the build-cache key.
     */
    fun getSchemaFilesIncludingDefault(
        schemaFiles: ConfigurableFileCollection,
        defaultSchemaFile: File,
        logger: Logger
    ): Set<File> {
        logger.info("Including default schema from: {}", defaultSchemaFile.absolutePath)
        return schemaFiles.files + defaultSchemaFile
    }
}
