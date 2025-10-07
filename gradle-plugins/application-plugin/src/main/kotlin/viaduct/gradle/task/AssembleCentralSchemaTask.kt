package viaduct.gradle.task

import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.slf4j.LoggerFactory
import viaduct.gradle.ViaductApplicationPlugin
import viaduct.gradle.ViaductApplicationPlugin.Companion.BUILTIN_SCHEMA_FILE
import viaduct.gradle.ViaductBasicSchemaValidator
import viaduct.graphql.utils.DefaultSchemaProvider

/**
 * This task gathers the various partitions of the schema and
 * stores them in a stable location. Based on that location it
 * generates the complete default schema in SDL format as a String
 * and stores it in a file.
 */
@CacheableTask
abstract class AssembleCentralSchemaTask
    @Inject
    constructor(
        private var fileSystemOperations: FileSystemOperations
    ) : DefaultTask() {
        init {
            group = "viaduct"
            description = "Collect schema files from all modules into a single directory."
        }

        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val schemaPartitions: ConfigurableFileCollection

        @get:OutputDirectory
        abstract val outputDirectory: DirectoryProperty

        @TaskAction
        fun taskAction() {
            fileSystemOperations.sync {
                from(schemaPartitions) {
                    into("partition")
                    include("**/*.graphqls")
                }
                into(outputDirectory.get())
            }
            val allSchemaFiles = outputDirectory.get().asFileTree.matching { include("**/*.graphqls") }.files

            val sdl = DefaultSchemaProvider.getDefaultSDL(existingSDLFiles = allSchemaFiles.toList())
            val sdlFile = outputDirectory.get().asFile.resolve(BUILTIN_SCHEMA_FILE)
            sdlFile.writeText(sdl)

            validateCompleteSchema(allSchemaFiles + sdlFile)
        }

        private fun validateCompleteSchema(schemaFiles: Collection<File>) {
            val logger = LoggerFactory.getLogger(ViaductApplicationPlugin::class.java)
            val validator = ViaductBasicSchemaValidator(logger)
            val errors = validator.validateSchema(schemaFiles)
            if (errors.isNotEmpty()) {
                errors.forEach { logger.error(it.message ?: it.toString()) }
                throw GradleException("GraphQL schema validation failed. See errors above.")
            } else {
                logger.info("GraphQL schema validation successful.")
            }
        }
    }
