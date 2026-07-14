package viaduct.gradle.task

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.slf4j.LoggerFactory
import viaduct.apiannotations.ExperimentalApi
import viaduct.gradle.ViaductApplicationPlugin
import viaduct.gradle.ViaductSchemaValidator
import viaduct.graphql.utils.DefaultSchemaFactory
import viaduct.service.api.scoping.SchemaScoping

@OptIn(ExperimentalApi::class)
abstract class ValidateSchemaExtensionsTask : DefaultTask() {
    init {
        group = "viaduct"
        description = "Validate schema extension files (schemabase + common) against Viaduct rules in isolation, without requiring module partitions. Run this to quickly check your schema extensions are well-formed."
    }

    private val logger = LoggerFactory.getLogger(ViaductApplicationPlugin::class.java)

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baseSchemaFiles: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val commonSchemaFiles: ConfigurableFileCollection

    /**
     * Mirrors `AssembleCentralSchemaTask.schemaScoping`. The preflight and assembly tasks must
     * agree on whether the framework emits the `@scope` directive; otherwise the preflight would
     * greenlight tenant SDL that assembly then rejects.
     */
    @get:Input
    abstract val schemaScoping: Property<SchemaScoping>

    @TaskAction
    fun taskAction() {
        val userFiles = (baseSchemaFiles + commonSchemaFiles).filter { it.exists() }.files.toList()

        val includeScopeDirective = schemaScoping.get().isScoped
        val sdl = DefaultSchemaFactory.getDefaultSDL(
            existingSDLFiles = userFiles,
            includeScopeDirective = includeScopeDirective,
        )
        val builtinFile = temporaryDir.resolve(ViaductApplicationPlugin.BUILTIN_SCHEMA_FILE)
        builtinFile.writeText(sdl)

        val allFiles = userFiles + builtinFile
        val validator = ViaductSchemaValidator(logger, extensionsOnly = true)
        val errors = validator.validateSchema(allFiles, excludeFromViaductValidation = listOf(builtinFile))

        if (errors.isNotEmpty()) {
            errors.forEach { logger.error(it.message ?: it.toString()) }
            throw GradleException("Schema extension validation failed. See errors above.")
        } else {
            logger.info("Schema extension validation successful.")
        }
    }
}
