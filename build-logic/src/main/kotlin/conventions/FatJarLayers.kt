package conventions

import com.github.jengelman.gradle.plugins.shadow.ShadowStats
import com.github.jengelman.gradle.plugins.shadow.relocation.RelocatePathContext
import com.github.jengelman.gradle.plugins.shadow.relocation.Relocator
import java.io.File
import java.util.zip.ZipFile

private val multiReleasePrefix = Regex("^META-INF/versions/\\d+/")

internal fun layerPayload(jars: Iterable<File>): Set<String> =
    jars.flatMapTo(mutableSetOf()) { jar ->
        ZipFile(jar).use { archive -> archive.entries().asSequence().map { it.name }.filter(::isLayerPayload).toList() }
    }

internal fun shippedPath(
    path: String,
    relocators: List<Relocator>,
    stats: ShadowStats
): String {
    val versionPrefix = if (path.endsWith(".class")) multiReleasePrefix.find(path)?.value.orEmpty() else ""
    val unversionedPath = path.removePrefix(versionPrefix)
    val relocator = relocators.firstOrNull { it.canRelocatePath(unversionedPath) } ?: return path
    return versionPrefix + relocator.relocatePath(RelocatePathContext(unversionedPath, stats))
}

private fun isLayerPayload(path: String): Boolean =
    !path.endsWith("/") &&
        (
            !path.startsWith("META-INF/") ||
                path.startsWith("META-INF/maven/") ||
                (multiReleasePrefix.containsMatchIn(path) && !path.endsWith("module-info.class"))
        )
