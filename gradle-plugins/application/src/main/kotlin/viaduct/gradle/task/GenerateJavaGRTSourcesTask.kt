package viaduct.gradle.task

import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkerExecutor
import viaduct.gradle.CodegenWorkAction
import viaduct.gradle.runCodegen

@CacheableTask
abstract class GenerateJavaGRTSourcesTask
    @Inject
    constructor(
        private val workerExecutor: WorkerExecutor
    ) : DefaultTask() {
        init {
            description = "Generate Java GRT source files from the central schema."
        }

        @get:Classpath
        abstract val classpath: ConfigurableFileCollection

        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val schemaFiles: ConfigurableFileCollection

        @get:OutputDirectory
        abstract val grtSourcesDirectory: DirectoryProperty

        @TaskAction
        fun taskAction() {
            val outputDir = grtSourcesDirectory.get().asFile
            if (outputDir.exists()) outputDir.deleteRecursively()
            outputDir.mkdirs()

            val resolverDiscardDir = temporaryDir.resolve("discard-resolvers").apply {
                deleteRecursively()
                mkdirs()
            }

            workerExecutor.runCodegen(
                classpath,
                CodegenWorkAction.MainClasses.JAVA_GRTS_GENERATOR,
                listOf(
                    "--schema_files",
                    schemaFiles.files.map(File::getAbsolutePath).sorted().joinToString(","),
                    "--grt_output_dir",
                    outputDir.absolutePath,
                    "--grt_package",
                    "viaduct.java.grts",
                    "--resolver_generated_dir",
                    resolverDiscardDir.absolutePath,
                    "--tenant_package",
                    "viaduct.java.grts",
                    "--include_root_types"
                )
            )
        }
    }
