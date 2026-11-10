package fixtures

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class GenerateFinalSchema : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val centralSchema: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val input = centralSchema.get().asFile.walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
        check("greeting: String" in input && "author: String" in input)
        check("FixtureFinal" !in input && "finalOnly" !in input)
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        output.resolve("final.graphqls").writeText("type FixtureFinal { value: String }\nextend interface PluginInputs { finalOnly: String }\n")
    }
}
