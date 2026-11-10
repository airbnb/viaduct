package viaduct.gradle.task

import graphql.GraphqlErrorBuilder
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.problems.Problems
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi
import viaduct.gradle.SchemaValidationProblems
import viaduct.gradle.ScopedSchemaValidator
import viaduct.gradle.ViaductSchemaValidator
import viaduct.gradle.ViaductScopesYaml
import viaduct.graphql.schema.SchemaProvenance

@OptIn(ExperimentalApi::class)
@InternalApi
@CacheableTask
abstract class AssembleFinalSchemaTask
    @Inject
    constructor(
        private val fileSystemOperations: FileSystemOperations,
    ) : DefaultTask() {
        @get:Inject
        abstract val problems: Problems

        @get:InputDirectory
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val centralSchemaDirectory: DirectoryProperty

        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val pluginSchemaFiles: ConfigurableFileCollection

        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val scopesFile: ConfigurableFileCollection

        @get:Classpath
        abstract val schemaValidationClasspath: ConfigurableFileCollection

        @get:OutputDirectory
        abstract val outputDirectory: DirectoryProperty

        init {
            group = "viaduct"
            description = "Assemble and validate the final application schema, including central-schema-derived plugin additions."
        }

        @TaskAction
        fun assemble() {
            fileSystemOperations.delete { delete(outputDirectory) }
            val contributors = pluginSchemaFiles.asFileTree.matching { include("**/*.graphqls") }.files.map { file ->
                pluginSchemaFiles.files.firstNotNullOfOrNull { root ->
                    if (file.toPath().startsWith(root.toPath())) {
                        val path = file.relativeTo(root).invariantSeparatorsPath
                        SchemaProvenance.parse(path)?.supplyingPlugin?.let { "$it [$path]" }
                    } else {
                        null
                    }
                } ?: throw GradleException("Invalid final plugin SDL provenance: $file")
            }.distinct().sorted().ifEmpty { listOf("com.airbnb.viaduct final assembly") }
            val staging = temporaryDir.resolve("schema")
            try {
                fileSystemOperations.sync {
                    from(centralSchemaDirectory)
                    from(pluginSchemaFiles) { include("**/*.graphqls") }
                    into(staging)
                    includeEmptyDirs = false
                    duplicatesStrategy = DuplicatesStrategy.FAIL
                }
            } catch (failure: GradleException) {
                throw GradleException("Final-schema contribution error from ${contributors.joinToString()}: ${failure.message}", failure)
            }
            val scopeInputs = scopesFile.files
            check(scopeInputs.size <= 1) { "Expected at most one scopes.yaml" }
            val scoping = ViaductScopesYaml.read(scopeInputs.firstOrNull())
            val reporting = SchemaValidationProblems(problems, staging, "final", contributors)
            val validator = ViaductSchemaValidator(
                logger,
                sourceRoot = staging,
                validateScopeConsistency = scoping.isScoped,
                validationClasspath = schemaValidationClasspath.files,
                finalContributors = contributors,
                reportDiagnostic = reporting::reportRule,
                reportStructural = reporting::reportStructural,
            )
            val schemaFiles = staging.walkTopDown().filter { it.isFile && it.extension == "graphqls" }.toList()
            val errors = validator.validateSchema(schemaFiles)
            if (errors.isNotEmpty()) {
                errors.forEach { logger.error(it.message) }
                throw GradleException("Final schema validation failed. See errors above.")
            }
            val scopedFailures = ScopedSchemaValidator.validateFiles(schemaFiles, staging, scoping)
            if (scopedFailures.isNotEmpty()) {
                scopedFailures.forEach {
                    val message = validator.finalSchemaMessage(it)
                    logger.error(message)
                    val error: GraphqlErrorBuilder<*> = GraphqlErrorBuilder.newError()
                    error.message(message)
                    reporting.reportStructural(error.build())
                }
                throw GradleException("Final scoped-schema validation failed. See errors above.")
            }
            fileSystemOperations.sync {
                from(staging)
                into(outputDirectory)
                includeEmptyDirs = false
            }
        }
    }
