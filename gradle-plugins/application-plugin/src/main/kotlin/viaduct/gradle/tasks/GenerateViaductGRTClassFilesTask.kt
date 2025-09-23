package viaduct.gradle.tasks

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider
import java.io.File

@CacheableTask
abstract class GenerateViaductGRTClassFilesTask : JavaExec() {

    init {
        // No group: don't want this to appear in task list
        description = "Generate compiled GRT class files from the central schema."
    }

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val schemaFiles: ConfigurableFileCollection

    @get:Input
    abstract val grtPackageName: Property<String>

    @get:OutputDirectory
    abstract val grtClassesDirectory: DirectoryProperty

    override fun exec() {
        argumentProviders.add(
            GrtArgs(
                schemaFiles.files.map(File::getAbsolutePath),
                grtPackageName.get(),
                grtClassesDirectory.get().asFile.absolutePath
            )
        )
        super.exec()
    }


    class GrtArgs(
        val schemaFiles: List<String>,
        val packageName: String,
        val generatedDirectory: String
    ) : CommandLineArgumentProvider {
        override fun asArguments(): Iterable<String> {
            return listOf(
                "--schema_files", schemaFiles.sorted().joinToString(","),
                "--pkg_for_generated_classes", packageName,
                "--generated_directory", generatedDirectory
            )
        }
    }
}