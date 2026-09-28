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
import viaduct.apiannotations.ExperimentalApi
import viaduct.gradle.SchemaContributionReconciler
import viaduct.gradle.ViaductApplicationPlugin
import viaduct.gradle.ViaductApplicationPlugin.Companion.BUILTIN_SCHEMA_FILE
import viaduct.gradle.ViaductSchemaValidator
import viaduct.gradle.ViaductScopesYaml
import viaduct.graphql.utils.DefaultSchemaFactory
import viaduct.service.api.scoping.SchemaScopeDefinitions
import viaduct.service.api.scoping.SchemaScoping

/**
 * This task gathers the various partitions of the schema and
 * stores them in a stable location. Based on that location it
 * generates the complete default schema in SDL format as a String
 * and stores it in a file.
 */
@OptIn(ExperimentalApi::class)
@CacheableTask
abstract class AssembleCentralSchemaTask
    @Inject
    constructor(
        private var fileSystemOperations: FileSystemOperations
    ) : DefaultTask() {
        init {
            group = "viaduct"
            description = "Merge and validate GraphQL schema files from all modules into a single central schema. Run this in CI to verify the complete schema is valid."
        }

        /** Schema partition files from individual viaduct-module projects. */
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val schemaPartitions: ConfigurableFileCollection

        /**
         * Base schema files from src/main/viaduct/schemabase directory.
         * These typically contain shared directives, interfaces, and common types
         * used across the application.
         */
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val baseSchemaFiles: ConfigurableFileCollection

        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val schemaContributionFiles: ConfigurableFileCollection

        /**
         * Common Schema files from src/viaduct/schema directory.
         * These contain global schema declarations including extensions to Query, Mutation,
         * and Subscription types that apply to the entire project, also shared comm
         *
         * Use this to define project-wide GraphQL schema definitions that are not specific to any module,
         * such as:
         * schema {
         *      query: CustomQuery
         *      mutation: CustomMutation
         *      subscription: CustomSubscription
         * }
         *
         * directive @common
         */
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val commonSchemaFiles: ConfigurableFileCollection

        @get:OutputDirectory
        abstract val outputDirectory: DirectoryProperty

        /**
         * The application's `scopes.yaml`, empty when the application declares no scopes.
         *
         * A file collection rather than a `RegularFileProperty` because the conventional path is wired
         * unconditionally and most applications have no such file: `@InputFile` fails validation for a
         * configured-but-absent path, while an empty collection is a legitimate state.
         */
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val scopesFile: ConfigurableFileCollection

        @TaskAction
        fun taskAction() {
            // Read before writing anything, so an invalid scopes.yaml leaves no half-built output.
            val declaredScopesFiles = scopesFile.files
            if (declaredScopesFiles.size > 1) {
                throw GradleException(
                    "Expected at most one ${SchemaScopeDefinitions.SOURCE_FILE_NAME}, but scopesFile holds " +
                        "${declaredScopesFiles.map { it.invariantSeparatorsPath }}.",
                )
            }
            val scoping = ViaductScopesYaml.read(declaredScopesFiles.firstOrNull())

            val reconciledBaseSchema = SchemaContributionReconciler.reconcile(
                baseSchemaFiles.filter { it.exists() }.files,
                schemaContributionFiles.filter { it.exists() }.files,
                temporaryDir.resolve("reconciled-schemabase"),
            )
            fileSystemOperations.sync {
                from(schemaPartitions) {
                    into("partition")
                    include("**/*.graphqls")
                }

                from(baseSchemaFiles) {
                    into("schemabase")
                    include("**/*.graphqls")
                }

                from(reconciledBaseSchema) {
                    into("schemabase/contributions")
                }

                from(commonSchemaFiles) {
                    into("common")
                    include("**/*.graphqls")
                }

                into(outputDirectory.get())
            }
            val allSchemaFiles = outputDirectory.get().asFileTree.matching { include("**/*.graphqls") }.files

            val sdl = DefaultSchemaFactory.getDefaultSDL(existingSDLFiles = allSchemaFiles.toList())
            val sdlFile = outputDirectory.get().asFile.resolve(BUILTIN_SCHEMA_FILE)
            sdlFile.writeText(sdl)

            validateCompleteSchema(
                schemaFiles = allSchemaFiles + sdlFile,
                excludeFromViaductValidation = listOf(sdlFile),
                scoping = scoping,
            )
        }

        private fun validateCompleteSchema(
            schemaFiles: Collection<File>,
            excludeFromViaductValidation: Collection<File>,
            scoping: SchemaScoping,
        ) {
            val logger = LoggerFactory.getLogger(ViaductApplicationPlugin::class.java)
            val validator = ViaductSchemaValidator(
                logger,
                validateScopeConsistency = scoping.isScoped,
            )
            val errors = validator.validateSchema(schemaFiles, excludeFromViaductValidation)
            if (errors.isNotEmpty()) {
                errors.forEach { logger.error(it.message ?: it.toString()) }
                throw GradleException("GraphQL schema validation failed. See errors above.")
            } else {
                logger.info("GraphQL schema validation successful.")
            }
        }
    }
