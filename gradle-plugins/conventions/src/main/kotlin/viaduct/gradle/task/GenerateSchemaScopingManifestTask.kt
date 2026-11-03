package viaduct.gradle.task

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
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi
import viaduct.gradle.ViaductScopesYaml
import viaduct.scoping.SchemaScopeDefinitions
import viaduct.scoping.SchemaScopingManifest

/**
 * Writes the application's schema scope definitions as the JSON manifest runtime reads, so the declared
 * scope universe reaches the schema filter instead of being inferred from the schema being filtered.
 *
 * An application with no `scopes.yaml` produces no manifest, which is what keeps inference in place for
 * every application that does not declare scopes.
 */
@OptIn(ExperimentalApi::class)
@CacheableTask
@InternalApi
abstract class GenerateSchemaScopingManifestTask
    @Inject
    constructor(
        private val fileSystemOperations: FileSystemOperations
    ) : DefaultTask() {
        init {
            group = "viaduct"
            description = "Write the declared schema scope definitions into the application's resources for runtime."
        }

        /**
         * The application's `scopes.yaml`, empty when the application declares no scopes.
         *
         * A file collection rather than a `RegularFileProperty` for the same reason as
         * [AssembleCentralSchemaTask.scopesFile]: `@InputFile` fails validation for a
         * configured-but-absent path, while an empty collection is a legitimate state.
         */
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val scopesFile: ConfigurableFileCollection

        /** Holds the manifest at its runtime resource path, so this task owns that path in one place. */
        @get:OutputDirectory
        abstract val outputDirectory: DirectoryProperty

        @TaskAction
        fun taskAction() {
            val declaredScopesFiles = scopesFile.files
            if (declaredScopesFiles.size > 1) {
                throw GradleException(
                    "Expected at most one ${SchemaScopeDefinitions.SOURCE_FILE_NAME}, but scopesFile holds " +
                        "${declaredScopesFiles.map { it.invariantSeparatorsPath }}.",
                )
            }
            val scoping = ViaductScopesYaml.read(declaredScopesFiles.firstOrNull())

            val staged = temporaryDir.resolve("staged")
            fileSystemOperations.delete { delete(staged) }
            if (scoping.isScoped) {
                staged.resolve(SchemaScopingManifest.RESOURCE_PATH).apply {
                    parentFile.mkdirs()
                    writeText(SchemaScopingManifest.encode(scoping))
                }
            }

            // sync rather than copy, so removing scopes.yaml removes the manifest instead of leaving a stale one.
            fileSystemOperations.sync {
                from(staged)
                into(outputDirectory)
            }
        }
    }
