package viaduct.gradle.common

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkerExecutor

/** Generates a random GraphQL schema via viaduct.arbitrary.cli.GenerateSchema; never up-to-date. */
abstract class GenerateArbitrarySchemaTask : DefaultTask() {
    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    @get:Classpath
    abstract val codegenClasspath: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun generate() {
        codegenClasspath.requireNonEmptyCodegenClasspath({ project.path }, "libs.viaduct.shared.arbitrary.cli")
        val output = outputFile.get().asFile
        output.parentFile?.mkdirs()
        workerExecutor.runCodegen(
            codegenClasspath,
            CodegenWorkAction.MainClasses.GENERATE_SCHEMA,
            listOf("--output", output.absolutePath)
        )
    }
}
