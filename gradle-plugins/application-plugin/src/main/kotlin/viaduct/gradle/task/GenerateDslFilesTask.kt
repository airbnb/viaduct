package viaduct.gradle.task

import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

@CacheableTask
abstract class GenerateDslFilesTask
    @Inject
    constructor(
        private var execOperations: ExecOperations
    ) : DefaultTask() {
        init {
            group = "viaduct"
            description = "Generate Kotlin DSL query builder files from the central schema."
        }

        @get:Input
        abstract val mainClass: Property<String>

        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val classpath: ConfigurableFileCollection

        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val schemaFiles: ConfigurableFileCollection

        @get:Input
        abstract val dslPackageName: Property<String>

        @get:OutputDirectory
        abstract val dslOutputDirectory: DirectoryProperty

        @TaskAction
        fun taskAction() {
            execOperations.javaexec {
                classpath = this@GenerateDslFilesTask.classpath
                mainClass.set(this@GenerateDslFilesTask.mainClass.get())
                argumentProviders.add {
                    listOf(
                        "--schema_files",
                        schemaFiles.files.map(File::getAbsolutePath).sorted().joinToString(","),
                        "--pkg_for_generated_classes",
                        dslPackageName.get(),
                        "--generated_directory",
                        dslOutputDirectory.get().asFile.absolutePath
                    )
                }
            }
        }
    }
