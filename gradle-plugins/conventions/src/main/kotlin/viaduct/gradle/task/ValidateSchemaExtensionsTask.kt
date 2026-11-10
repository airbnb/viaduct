package viaduct.gradle.task

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.slf4j.LoggerFactory
import viaduct.apiannotations.InternalApi
import viaduct.gradle.ViaductApplicationConventionsPlugin
import viaduct.gradle.ViaductSchemaValidator
import viaduct.graphql.schema.SchemaProvenance
import viaduct.graphql.schema.graphqljava.readTypesFromFiles
import viaduct.graphql.utils.DefaultSchemaFactory

@InternalApi
abstract class ValidateSchemaExtensionsTask
    @Inject
    constructor(private val fileSystemOperations: FileSystemOperations) : DefaultTask() {
        init {
            group = "viaduct"
            description = "Validate schema extension files (schemabase + common) against Viaduct rules in isolation, without requiring module partitions. Run this to quickly check your schema extensions are well-formed."
        }

        private val logger = LoggerFactory.getLogger(ViaductApplicationConventionsPlugin::class.java)

        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val baseSchemaFiles: ConfigurableFileCollection

        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val schemaContributionFiles: ConfigurableFileCollection

        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val commonSchemaFiles: ConfigurableFileCollection

        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val pluginSchemaFiles: ConfigurableFileCollection

        @TaskAction
        fun taskAction() {
            val staging = temporaryDir.resolve("schema-inputs")
            fileSystemOperations.sync {
                into(staging)
                duplicatesStrategy = DuplicatesStrategy.FAIL
                from(baseSchemaFiles) { into(SchemaProvenance.applicationSourceDirectory("schemabase")) }
                from(commonSchemaFiles) { into(SchemaProvenance.applicationSourceDirectory("common")) }
                from(schemaContributionFiles)
                from(pluginSchemaFiles)
                include("**/*.graphqls")
                includeEmptyDirs = false
            }
            val userFiles = staging.walkTopDown().filter { it.isFile && it.extension == "graphqls" }.toList()

            val sdl = DefaultSchemaFactory.getDefaultSDL(existingRegistry = readTypesFromFiles(userFiles, staging))
            val builtinFile = staging.resolve(ViaductApplicationConventionsPlugin.BUILTIN_SCHEMA_FILE)
            check(!builtinFile.exists()) {
                "Conflicting plugin SDL destination '${ViaductApplicationConventionsPlugin.BUILTIN_SCHEMA_FILE}': a plugin input and Viaduct built-ins both claim this path."
            }
            builtinFile.parentFile.mkdirs()
            builtinFile.writeText(sdl)

            val allFiles = userFiles + builtinFile
            val validator = ViaductSchemaValidator(logger, extensionsOnly = true, sourceRoot = staging)
            val errors = validator.validateSchema(allFiles, excludeFromViaductValidation = listOf(builtinFile))

            if (errors.isNotEmpty()) {
                errors.forEach { logger.error(it.message ?: it.toString()) }
                throw GradleException("Schema extension validation failed. See errors above.")
            } else {
                logger.info("Schema extension validation successful.")
            }
        }
    }
