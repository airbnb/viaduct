package viaduct.gradle.task

import graphql.parser.MultiSourceReader
import graphql.schema.GraphQLSchema
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
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import viaduct.apiannotations.ExperimentalApi
import viaduct.gradle.ViaductApplicationPlugin
import viaduct.gradle.ViaductApplicationPlugin.Companion.BUILTIN_SCHEMA_FILE
import viaduct.gradle.ViaductSchemaValidator
import viaduct.graphql.schema.validation.ValidationErrorCodes
import viaduct.graphql.scopes.ScopedSchemaValidator
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
            val scoping = schemaScoping.get()
            val sdl = DefaultSchemaFactory.getDefaultSDL(
                existingSDLFiles = allSchemaFiles.toList(),
                includeScopeDirective = scoping.isScoped,
            )
            val sdlFile = outputDirectory.get().asFile.resolve(BUILTIN_SCHEMA_FILE)
            sdlFile.writeText(sdl)

            validateCompleteSchema(
                schemaFiles = allSchemaFiles + sdlFile,
                excludeFromViaductValidation = listOf(sdlFile),
                validScopes = scoping.scopeUniverse
            )

            // Slice 5 of #361 — for scoped applications, materialize each distinct declared scope
            // set (plus the always-included universe set) and assert introspectability at build
            // time. This is the counterpart to slice 4's build-time `@scope` gating: slice 4 fixes
            // which `@scope` decorations get emitted; slice 5 fixes what the resulting scoped
            // projections must look like when applied. Unscoped applications skip this entirely —
            // there is no scope directive to filter by.
            if (scoping.isScoped) {
                validateScopedSchemas(allSchemaFiles + sdlFile, scoping)
            }
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

        /**
         * Materialize each distinct scope set and assert it introspects cleanly.
         *
         * The set of scope sets iterated is:
         * - every non-empty `scopedSchemas.values` entry (declared scoped schemas), AND
         * - the full `scopeUniverse` (always included — see D3 deviation from spec's
         *   `if (scopeSet.isEmpty()) continue`, filed as a heads-up on issue #374).
         *
         * Deduplication is on the *scope set*, not the alias name — aliases carry no validation
         * identity. An empty-set scoped-schema entry (`scopedSchema("FULL_ALIAS")`) contributes
         * nothing to the explicit list; it collapses into the always-added universe set, giving
         * empty-alias entries well-defined "materialize as base" semantics.
         *
         * Failures across all scope sets are aggregated before throwing, so the operator gets one
         * report per build rather than one per Gradle re-run.
         */
        private fun validateScopedSchemas(
            schemaFiles: Collection<File>,
            scoping: SchemaScoping,
        ) {
            val logger = LoggerFactory.getLogger(ViaductApplicationPlugin::class.java)
            val inputSchema = parseSchemaForScopedValidation(schemaFiles, logger)
            val validator = ScopedSchemaValidator(inputSchema, scoping.scopeUniverse.toSortedSet())

            // Iterate in canonical (sorted) order so INFO/WARN/ERROR log ordering is stable
            // across runs. `Set<Set<String>>` iteration order is undefined; without sorting, CI
            // log diffs and Gradle cached-output comparisons would flap.
            val toMaterialize: List<Set<String>> =
                (scoping.scopedSchemas.values.filter { it.isNotEmpty() } + listOf(scoping.scopeUniverse))
                    .toSet()
                    .sortedBy { it.toSortedSet().toString() }

            val failures = mutableListOf<Pair<String, ScopedSchemaValidator.Failure>>()
            for (scopeSet in toMaterialize) {
                val label = scopeSet.toSortedSet().toString()
                val startNanos = System.nanoTime()
                val setFailures = validator.validate(scopeSet)
                val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
                if (setFailures.isEmpty()) {
                    // INFO — visible under `--info`. Operators inspecting a slow build need to see
                    // per-set materialization work; without this line, time spent here looks like
                    // an unattributed hang inside `assembleViaductCentralSchema`.
                    logger.info("Validated scoped schema for scope set {} in {} ms", label, elapsedMs)
                } else {
                    // WARN so it surfaces without --info as well, since we're about to fail the
                    // build with an aggregated error and want the operator to see which set
                    // contributed.
                    logger.warn("Scoped schema for scope set {} failed after {} ms", label, elapsedMs)
                    setFailures.forEach { failures.add(label to it) }
                }
            }

            if (failures.isNotEmpty()) {
                failures.forEach { (label, failure) ->
                    logger.error(
                        "[{}] scope set {}: {}",
                        ValidationErrorCodes.SCOPED_SCHEMA_INTROSPECTION_FAILED,
                        label,
                        failure.message,
                    )
                }
                val distinctFailingSets = failures.map { it.first }.distinct().size
                throw GradleException(
                    "Scoped schema validation failed for $distinctFailingSets scope set(s). See errors above."
                )
            }
        }

        /**
         * Parse the assembled schema files into a `GraphQLSchema` suitable for
         * `ScopedSchemaValidator`. Throws `GradleException` on parse failure rather than
         * returning null — silently skipping slice-5 validation on a parse failure would be a
         * log-and-continue false negative: the operator would see a SUCCESS build with scoped
         * materialization silently bypassed. `validateCompleteSchema` normally throws first on
         * a bad schema, but this method must not depend on that ordering to fail loudly.
         */
        private fun parseSchemaForScopedValidation(
            schemaFiles: Collection<File>,
            logger: Logger,
        ): GraphQLSchema {
            val reader = MultiSourceReader.newMultiSourceReader().apply {
                schemaFiles.forEach { file ->
                    reader(StringReader(file.readText(Charsets.UTF_8)), file.path)
                }
            }.trackData(true).build()

            return try {
                val registry = SchemaParser().parse(reader)
                UnExecutableSchemaGenerator.makeUnExecutableSchema(registry)
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                logger.error(
                    "[{}] failed to parse assembled schema for scoped materialization: {}",
                    ValidationErrorCodes.SCOPED_SCHEMA_INTROSPECTION_FAILED,
                    msg,
                )
                throw GradleException(
                    "Scoped schema validation could not run: failed to parse assembled schema — $msg"
                )
            }
        }
    }
