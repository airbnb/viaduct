package viaduct.gradle.common

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkerExecutor

/** Generates a schema seeded from the contents of [seedInputs] and [seedVariant]. */
@CacheableTask
abstract class GenerateArbitrarySchemaTask : DefaultTask() {
    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    @get:Classpath
    abstract val codegenClasspath: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val seedInputs: ConfigurableFileCollection

    @get:Input
    abstract val seedVariant: Property<Long>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        codegenClasspath.requireNonEmptyCodegenClasspath({ project.path }, "libs.viaduct.shared.arbitrary.cli")
        val output = outputFile.get().asFile
        output.parentFile?.mkdirs()
        val seedInputArgs = seedInputs.files
            .filter { it.isFile }
            .flatMap { listOf("--seed-input", it.absolutePath) }
        workerExecutor.runCodegen(
            codegenClasspath,
            CodegenWorkAction.MainClasses.GENERATE_SCHEMA,
            listOf("--output", output.absolutePath, "--seed-variant", seedVariant.get().toString()) + seedInputArgs
        )
    }
}
