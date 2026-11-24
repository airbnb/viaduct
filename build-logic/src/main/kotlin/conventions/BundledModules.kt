package conventions

import com.github.jengelman.gradle.plugins.shadow.ShadowStats
import com.github.jengelman.gradle.plugins.shadow.relocation.Relocator
import java.io.File
import java.util.zip.ZipFile
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult

internal fun modulesWithClassesIn(
    jar: File,
    artifacts: List<ResolvedArtifactResult>,
    relocators: List<Relocator>
): List<String> =
    modulesWithClassesIn(
        jar,
        artifacts.mapNotNull { artifact ->
            (artifact.id.componentIdentifier as? ModuleComponentIdentifier)?.let { "${it.group}:${it.module}" to artifact.file }
        },
        relocators
    )

internal fun modulesWithClassesIn(
    jar: File,
    moduleFiles: Iterable<Pair<String, File>>,
    relocators: List<Relocator>
): List<String> {
    val shipped = ZipFile(jar).use { archive -> archive.entries().asSequence().map { it.name }.toSet() }
    val stats = ShadowStats()
    return moduleFiles.filter { (_, file) ->
        file.isFile && ZipFile(file).use { archive ->
            archive.entries().asSequence().map { it.name }
                .filter { it.endsWith(".class") && !it.endsWith("module-info.class") }
                .any { shippedPath(it, relocators, stats) in shipped }
        }
    }.map { (module, _) -> module }.distinct().sorted()
}
