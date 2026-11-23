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
import viaduct.utils.resources.ViaductResourceClaims
import viaduct.utils.resources.ViaductResourcePaths

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
        val claims = ViaductResourceClaims()
        contributions.forEach { claims.claim(ViaductResourcePaths.fromArchive(it.path), it.origin) }
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
            artifact.isDirectory -> {
                val legacy = artifact.resolve(LEGACY_REGISTRY_RESOURCE_ROOT)
                    .listFiles { file -> file.isFile && file.extension == "json" }
                    ?.minByOrNull(File::getName)
                if (legacy != null) {
                    rejectLegacyManifest(artifact, "$LEGACY_REGISTRY_RESOURCE_ROOT/${legacy.name}")
                }
                artifact.resolve(REGISTRY_RESOURCE_ROOT).walkTopDown()
                    .filter { it.isFile && it.extension == "json" }
                    .map { file ->
                        val path = file.relativeTo(artifact).invariantSeparatorsPath
                        file.inputStream().use { input -> registryContribution(ViaductResourcePaths.archive(path), artifact.path, input) }
                    }
                    .toList()
            }
            artifact.isFile && artifact.extension == "jar" ->
                ZipFile(artifact).use { zip ->
                    zip.entries().asSequence()
                        .filter { !it.isDirectory && it.name.endsWith(".json") }
                        .filter { entry ->
                            if (entry.name.substringBeforeLast('/') == LEGACY_REGISTRY_RESOURCE_ROOT) {
                                rejectLegacyManifest(artifact, entry.name)
                            }
                            entry.name.startsWith("META-INF/$REGISTRY_RESOURCE_ROOT/")
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

    private fun rejectLegacyManifest(
        artifact: File,
        path: String
    ): Nothing =
        throw IllegalArgumentException(
            "Legacy Viaduct execution manifest '$path' found in '${artifact.path}'. " +
                "Rebuild the module with a Viaduct module plugin compatible with this application plugin."
        )

    private fun registryContribution(
        path: String,
        origin: String,
        input: InputStream,
    ): RegistryContribution {
        ViaductResourcePaths.fromArchive(path)
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
        const val LEGACY_REGISTRY_RESOURCE_ROOT = "META-INF/viaduct/modules"
        const val REGISTRY_RESOURCE_ROOT = ViaductResourcePaths.MANIFEST_ROOT
    }
}
