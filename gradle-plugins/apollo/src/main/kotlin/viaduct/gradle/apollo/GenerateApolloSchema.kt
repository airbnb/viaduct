package viaduct.gradle.apollo

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class GenerateApolloSchema : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val centralSchemaDirectory: DirectoryProperty

    @get:Input
    abstract val keyDirectiveName: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    init {
        keyDirectiveName.convention("key")
    }

    @TaskAction
    fun generate() {
        val output = outputFile.get().asFile
        output.delete()
        val sdl = ApolloSchemaGenerator.generate(centralSchemaDirectory.get().asFile, keyDirectiveName.get())
        output.parentFile.mkdirs()
        output.writeText(sdl, Charsets.UTF_8)
    }
}
