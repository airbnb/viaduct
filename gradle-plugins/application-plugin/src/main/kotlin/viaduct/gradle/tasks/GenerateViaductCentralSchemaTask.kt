package viaduct.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import viaduct.gradle.ViaductApplicationPlugin.Companion.BUILTIN_SCHEMA_FILE
import viaduct.graphql.utils.DefaultSchemaProvider
import javax.inject.Inject

@CacheableTask
abstract class GenerateViaductCentralSchemaTask @Inject constructor(
    private var fileSystemOperations: FileSystemOperations
) : DefaultTask() {

    init {
        group = "viaduct"
        description = "Collect schema files from all modules into a single directory."
    }

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val schemaPartitions: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun taskAction() {
        fileSystemOperations.sync {
            // Bring in partitions under a stable prefix
            from(schemaPartitions) {
                into("partition")
                include("**/*.graphqls")
            }
            into(outputDirectory.get())
        }

        val allSchemaFiles = outputDirectory.get().asFileTree.matching { include("**/*.graphqls") }.files
        val sdl = DefaultSchemaProvider.getDefaultSDL(existingSDLFiles = allSchemaFiles.toList())
        outputDirectory.get().asFile.resolve(BUILTIN_SCHEMA_FILE).writeText(sdl)
    }

}