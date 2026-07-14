package viaduct.gradle.task

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
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import viaduct.apiannotations.InternalApi

@InternalApi
@CacheableTask
abstract class AssembleSchemaPartitionTask
    @Inject
    constructor(
        private var fileSystemOperations: FileSystemOperations
    ) : DefaultTask() {
        init {
            description = "Copies this module's .graphqls files into a structured partition to be merged into the application's central schema. (this task is useful for debugging but is NOT part of our stable API so don't use it in your CI scripts)."
        }

        @get:Input
        abstract val prefixPath: Property<String>

        /**
         * The schema source directory. Declared `@Internal` so Gradle does not perform its own
         * "directory must exist" validation; our `@TaskAction` provides a Viaduct-specific error
         * instead. File changes are tracked for caching via [schemaFiles].
         */
        @get:Internal
        abstract val graphqlSrcDir: DirectoryProperty

        /**
         * The `.graphqls` files inside [graphqlSrcDir], used by Gradle for up-to-date checking
         * and build caching. An empty collection is valid here; [taskAction] checks for and
         * reports missing or empty schema directories explicitly.
         */
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val schemaFiles: ConfigurableFileCollection

        @get:OutputDirectory
        abstract val outputDirectory: DirectoryProperty

        @TaskAction
        fun taskAction() {
            val schemaDir = graphqlSrcDir.get().asFile
            when {
                !schemaDir.exists() ->
                    throw GradleException(
                        "Viaduct module does not have a schema directory at " +
                            "'src/main/viaduct/schema'. " +
                            "Every Viaduct module must define at least one .graphqls schema file. " +
                            "Create the directory and add your schema."
                    )
                schemaDir.walkTopDown().none { it.isFile && it.extension == "graphqls" } ->
                    throw GradleException(
                        "Viaduct module has a schema directory at " +
                            "'src/main/viaduct/schema' but it contains no .graphqls files. " +
                            "Every Viaduct module must define at least one .graphqls schema file."
                    )
            }
            fileSystemOperations.sync { spec ->
                spec.from(graphqlSrcDir.get()) { copySpec ->
                    copySpec.include("**/*.graphqls")
                    copySpec.into(prefixPath.get())
                }
                spec.into(outputDirectory.get())
                spec.includeEmptyDirs = false
            }
        }
    }
