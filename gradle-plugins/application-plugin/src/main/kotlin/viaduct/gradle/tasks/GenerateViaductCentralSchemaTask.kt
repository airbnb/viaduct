package viaduct.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import viaduct.gradle.ViaductApplicationPlugin.Companion.BUILTIN_SCHEMA_FILE
import javax.inject.Inject

@DisableCachingByDefault(because = "Does only copying of files and writing some pre-calculated input into one file")
abstract class GenerateViaductCentralSchemaTask @Inject constructor(
    private var fileSystemOperations: FileSystemOperations
) : DefaultTask() {

    init {
        group = "viaduct"
        description = "Collect schema files from all modules into a single directory."
    }

    @get:Input
    abstract val sdl: Property<String>

    @get:InputFiles
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

        outputDirectory.get().asFile.resolve(BUILTIN_SCHEMA_FILE).writeText(sdl.get())
    }

}