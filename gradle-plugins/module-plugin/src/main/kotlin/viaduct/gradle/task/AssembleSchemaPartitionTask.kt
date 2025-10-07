package viaduct.gradle.task

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class AssembleSchemaPartitionTask
    @Inject
    constructor(
        private var fileSystemOperations: FileSystemOperations
    ) : DefaultTask() {
        init {
            // No group: don't want this to appear in task list
            description = "Prepare this module's schema partition."
        }

        @get:Input
        abstract val prefixPath: Property<String>

        @get:InputDirectory
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val graphqlSrcDir: DirectoryProperty

        @get:OutputDirectory
        abstract val outputDirectory: DirectoryProperty

        @TaskAction
        fun taskAction() {
            fileSystemOperations.sync {
                from(graphqlSrcDir.get()) {
                    include("**/*.graphqls")
                    into(prefixPath.get())
                }
                into(outputDirectory.get())
                includeEmptyDirs = false
            }
        }
    }
