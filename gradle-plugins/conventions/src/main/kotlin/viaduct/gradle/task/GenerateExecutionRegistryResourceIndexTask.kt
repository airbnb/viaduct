package viaduct.gradle.task

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import viaduct.apiannotations.InternalApi
import viaduct.bootstrap.ConfigKey
import viaduct.bootstrap.ExecutionRegistryConfigFile
import viaduct.bootstrap.ViaductResourceIndexes

@CacheableTask
@InternalApi
abstract class GenerateExecutionRegistryResourceIndexTask : DefaultTask() {
    @get:Classpath
    abstract val moduleClasspath: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val contributions = moduleClasspath.files.sortedBy(File::getPath).flatMap(::registryContributions)
        val duplicatePaths = contributions.groupBy(RegistryContribution::path).filterValues { it.size > 1 }
        require(duplicatePaths.isEmpty()) {
            "Duplicate execution registry resource contributors:\n" +
                duplicatePaths.entries.sortedBy { it.key }.joinToString("\n") { (path, entries) ->
                    "  $path from ${entries.map(RegistryContribution::origin).sorted()}"
                }
        }
        val duplicateKeys = contributions.groupBy(RegistryContribution::key).filterValues { it.size > 1 }
        require(duplicateKeys.isEmpty()) {
            "Duplicate execution registry config identities:\n" +
                duplicateKeys.entries.sortedBy { it.key.toString() }.joinToString("\n") { (key, entries) ->
                    "  $key from ${entries.map { "${it.path} in ${it.origin}" }.sorted()}"
                }
        }
        writeIndex(
            outputDirectory.get().asFile,
            ViaductResourceIndexes.EXECUTION_REGISTRY,
            contributions.map(RegistryContribution::path).sorted(),
        )
    }

    private fun registryContributions(artifact: File): List<RegistryContribution> =
        when {
            artifact.isDirectory ->
                artifact.resolve(REGISTRY_RESOURCE_ROOT).walkTopDown()
                    .filter { it.isFile && it.extension == "json" }
                    .map { file ->
                        val path = file.relativeTo(artifact).invariantSeparatorsPath
                        file.inputStream().use { input -> registryContribution(path, artifact.path, input) }
                    }
                    .toList()
            artifact.isFile && artifact.extension == "jar" ->
                ZipFile(artifact).use { zip ->
                    zip.entries().asSequence()
                        .filter { entry ->
                            !entry.isDirectory &&
                                entry.name.startsWith("$REGISTRY_RESOURCE_ROOT/") &&
                                entry.name.endsWith(".json")
                        }
                        .map { entry ->
                            zip.getInputStream(entry).use { input ->
                                registryContribution(entry.name, artifact.path, input)
                            }
                        }
                        .toList()
                }
            else -> emptyList()
        }

    private fun registryContribution(
        path: String,
        origin: String,
        input: InputStream,
    ): RegistryContribution {
        val config = ExecutionRegistryConfigFile.parse(input)
        val tenantName = config.tenantName?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Execution registry resource '$path' in '$origin' must include a non-blank tenantName")
        val apiName = config.apiName?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Execution registry resource '$path' in '$origin' must include a non-blank apiName")
        return RegistryContribution(path, origin, ConfigKey(tenantName, apiName))
    }

    private data class RegistryContribution(
        val path: String,
        val origin: String,
        val key: ConfigKey,
    )

    private companion object {
        const val REGISTRY_RESOURCE_ROOT = "META-INF/viaduct/modules"
    }
}
