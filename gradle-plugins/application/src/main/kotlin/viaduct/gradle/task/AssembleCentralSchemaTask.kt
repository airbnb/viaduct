package viaduct.gradle.task

import graphql.parser.MultiSourceReader
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import java.io.File
import java.io.StringReader
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.slf4j.LoggerFactory
import viaduct.gradle.ViaductApplicationPlugin
import viaduct.gradle.ViaductApplicationPlugin.Companion.BUILTIN_SCHEMA_FILE
import viaduct.gradle.ViaductSchemaValidator
import viaduct.graphql.scopes.errors.SchemaScopeValidationError
import viaduct.graphql.schema.scopes.NoScopesMode
import viaduct.graphql.schema.scopes.ScopedMode
import viaduct.graphql.utils.DefaultSchemaFactory

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

        /** Scope universe declared via [ViaductApplicationExtension.declaredSchemaScopes]. */
        @get:Input
        abstract val scopeUniverse: SetProperty<String>

        /**
         * Serialised scoped-schema entries — each entry is "id=scope1,scope2,..." (scopes sorted).
         * Mirrors [ViaductApplicationExtension.scopedSchemaEntries].
         */
        @get:Input
        abstract val scopedSchemaEntries: SetProperty<String>

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

            val sdl = DefaultSchemaFactory.getDefaultSDL(existingSDLFiles = allSchemaFiles.toList())
            val sdlFile = outputDirectory.get().asFile.resolve(BUILTIN_SCHEMA_FILE)
            sdlFile.writeText(sdl)

            val allFilesIncludingBuiltin = allSchemaFiles + sdlFile
            validateCompleteSchema(
                schemaFiles = allFilesIncludingBuiltin,
                excludeFromViaductValidation = listOf(sdlFile)
            )

            val scopeMode = parseScopeMode()
            if (scopeMode is ScopedMode) {
                runScopePipeline(allFilesIncludingBuiltin.toList(), scopeMode)
            }
        }

        private fun parseScopeMode(): viaduct.graphql.schema.scopes.ScopeMode {
            val universe = scopeUniverse.getOrElse(emptySet())
            if (universe.isEmpty()) return NoScopesMode
            val schemas = scopedSchemaEntries.getOrElse(emptySet()).associate { entry ->
                val eqIdx = entry.indexOf('=')
                val id = entry.substring(0, eqIdx)
                val scopesStr = entry.substring(eqIdx + 1)
                id to if (scopesStr.isEmpty()) emptySet() else scopesStr.split(",").toSet()
            }
            return ScopedMode(scopeUniverse = universe, scopedSchemas = schemas)
        }

        private fun runScopePipeline(allFiles: List<File>, scopedMode: ScopedMode) {
            val logger = LoggerFactory.getLogger(ViaductApplicationPlugin::class.java)

            // Phase 2: build unexecutable schema + create pipeline (scope directive validation happens here)
            val readerBuilder = MultiSourceReader.newMultiSourceReader()
            allFiles.forEach { file -> readerBuilder.reader(StringReader(file.readText()), file.path) }
            val registry = SchemaParser().parse(readerBuilder.build())
            val fullSchema = UnExecutableSchemaGenerator.makeUnExecutableSchema(registry)
            val pipeline = ScopeMaterializationPipeline(fullSchema, scopedMode.scopeUniverse.toSortedSet())

            // Unique scope sets across all declared scoped schemas
            val uniqueScopeSets: Set<Set<String>> = scopedMode.scopedSchemas.values.toSet()

            // Phase 3: materialize + validate each unique scope set
            for (scopeSet in uniqueScopeSets) {
                val materialized = try {
                    pipeline.materialize(scopeSet)
                } catch (e: SchemaScopeValidationError) {
                    throw GradleException("Scope validation failed for scope set $scopeSet: ${e.message}", e)
                }
                validateScopeSchemaWithIntrospection(materialized, scopeSet)
                logger.info("Scope set {} validated successfully.", scopeSet)
            }

            // Phase 4: emit resource file
            writeScopeResourceFile(outputDirectory.get().asFile, scopedMode)
            logger.info(
                "Scope resource file written to {}/META-INF/viaduct/schema-scoping.json",
                outputDirectory.get().asFile.absolutePath
            )
        }

        private fun validateCompleteSchema(
            schemaFiles: Collection<File>,
            excludeFromViaductValidation: Collection<File> = emptyList()
        ) {
            val logger = LoggerFactory.getLogger(ViaductApplicationPlugin::class.java)
            val validator = ViaductSchemaValidator(logger)
            val errors = validator.validateSchema(schemaFiles, excludeFromViaductValidation)
            if (errors.isNotEmpty()) {
                errors.forEach { logger.error(it.message ?: it.toString()) }
                throw GradleException("GraphQL schema validation failed. See errors above.")
            } else {
                logger.info("GraphQL schema validation successful.")
            }
        }
    }
