package viaduct.gradle.task

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import viaduct.apiannotations.InternalApi
import viaduct.bootstrap.ViaductResourceIndexes
import viaduct.gradle.ViaductApplicationConventionsPlugin
import viaduct.utils.resources.ViaductResourceClaims
import viaduct.utils.resources.ViaductResourcePaths

@CacheableTask
@InternalApi
abstract class GenerateSchemaResourceIndexTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val centralSchemaDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val schemaRoot = centralSchemaDirectory.get().asFile
        val claims = ViaductResourceClaims()
        val schemaPaths =
            schemaRoot.walkTopDown()
                .filter(File::isFile)
                .map { it.relativeTo(schemaRoot).invariantSeparatorsPath }
                .filter { it.endsWith(".graphqls") && it != ViaductResourcePaths.atStage(ViaductApplicationConventionsPlugin.BUILTIN_SCHEMA_FILE, "final-schema") }
                .map { path ->
                    require(path.startsWith("viaduct/final-schema/")) { "Invalid final schema resource: $path" }
                    claims.claim(path, schemaRoot.toString())
                    ViaductResourcePaths.archive(path)
                }
                .sorted()
                .toList()
        writeIndex(outputDirectory.get().asFile, ViaductResourceIndexes.SCHEMA, schemaPaths)
    }
}

internal fun writeIndex(
    outputRoot: File,
    resourcePath: String,
    paths: List<String>,
) {
    outputRoot.deleteRecursively()
    outputRoot.resolve(ViaductResourcePaths.fromArchive(resourcePath)).apply {
        parentFile.mkdirs()
        writeText(paths.joinToString(separator = "\n", postfix = if (paths.isEmpty()) "" else "\n"))
    }
}
