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
): List<String> {
    val shipped = ZipFile(jar).use { archive -> archive.entries().asSequence().map { it.name }.toSet() }
    val stats = ShadowStats()
    return artifacts.mapNotNull { artifact ->
        val module = artifact.id.componentIdentifier as? ModuleComponentIdentifier ?: return@mapNotNull null
        val shipsAClass = artifact.file.isFile && ZipFile(artifact.file).use { archive ->
            archive.entries().asSequence().map { it.name }
                .filter { it.endsWith(".class") && !it.endsWith("module-info.class") }
                .any { shippedPath(it, relocators, stats) in shipped }
        }
        if (shipsAClass) "${module.group}:${module.module}" else null
    }.distinct().sorted()
}
