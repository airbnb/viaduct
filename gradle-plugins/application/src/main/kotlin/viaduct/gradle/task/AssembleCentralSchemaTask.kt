package viaduct.gradle.task

import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.slf4j.LoggerFactory
import viaduct.apiannotations.ExperimentalApi
import viaduct.gradle.ViaductApplicationPlugin
import viaduct.gradle.ViaductApplicationPlugin.Companion.BUILTIN_SCHEMA_FILE
import viaduct.gradle.ViaductSchemaValidator
import viaduct.graphql.utils.DefaultSchemaFactory
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
         * The application's full schema-scoping declaration (universe + declared scoped schemas),
         * sourced from `viaductApplication.schemaScoping`. Slice 3 only reads `scopeUniverse` to
         * feed `ScopeUsageRule`; the full shape is on the task input because downstream slices
         * read the `scopedSchemas` map from the same task surface (materialization in slice 5,
         * JSON-manifest emission in slice 8), and pre-binding the full shape avoids an in-flight
         * task-input migration in a future PR.
         *
         * Empty (`SchemaScoping.EMPTY`) when the application has not opted into schema scoping;
         * `ScopeUsageRule` is bypassed in that case.
         */
        @get:Input
        abstract val schemaScoping: Property<SchemaScoping>

        @TaskAction
        fun taskAction() {
            fileSystemOperations.sync {
                from(schemaPartitions) {
                    into("partition")
                    include("**/*.graphqls")
                }

                from(baseSchemaFiles) {
                    into("schemabase")
                    include("**/*.graphqls")
                }

                from(commonSchemaFiles) {
                    into("common")
                    include("**/*.graphqls")
                }

                into(outputDirectory.get())
            }
            val allSchemaFiles = outputDirectory.get().asFileTree.matching { include("**/*.graphqls") }.files

            // Gate the built-in `@scope` decoration on whether this application has opted into
            // schema scoping. When `isScoped` is false, none of the framework-generated types
            // (Node, PageInfo, synthetic root types) carry `@scope`, the `@scope` directive
            // definition is omitted, and tenant schemas cannot use `@scope` — enforcement is
            // moved to build time.
            val includeScopeDirective = schemaScoping.get().isScoped
            val sdl = DefaultSchemaFactory.getDefaultSDL(
                existingSDLFiles = allSchemaFiles.toList(),
                includeScopeDirective = includeScopeDirective,
            )
            val sdlFile = outputDirectory.get().asFile.resolve(BUILTIN_SCHEMA_FILE)
            sdlFile.writeText(sdl)

            validateCompleteSchema(
                schemaFiles = allSchemaFiles + sdlFile,
                excludeFromViaductValidation = listOf(sdlFile),
                validScopes = schemaScoping.get().scopeUniverse
            )
        }

        private fun validateCompleteSchema(
            schemaFiles: Collection<File>,
            excludeFromViaductValidation: Collection<File> = emptyList(),
            validScopes: Set<String> = emptySet()
        ) {
            val logger = LoggerFactory.getLogger(ViaductApplicationPlugin::class.java)
            val validator = ViaductSchemaValidator(logger)
            val errors = validator.validateSchema(schemaFiles, excludeFromViaductValidation, validScopes)
            if (errors.isNotEmpty()) {
                errors.forEach { logger.error(it.message ?: it.toString()) }
                throw GradleException("GraphQL schema validation failed. See errors above.")
            } else {
                logger.info("GraphQL schema validation successful.")
            }
        }
    }
