package conventions

import com.github.jengelman.gradle.plugins.shadow.ShadowStats
import com.github.jengelman.gradle.plugins.shadow.relocation.RelocatePathContext
import com.github.jengelman.gradle.plugins.shadow.relocation.Relocator
import com.github.jengelman.gradle.plugins.shadow.relocation.SimpleRelocator
import groovy.util.Node
import groovy.util.NodeList
import groovy.xml.XmlParser
import java.io.File
import java.util.jar.JarFile
import java.util.zip.ZipFile

private val multiReleasePrefix = Regex("^META-INF/versions/\\d+/")

internal const val RELOCATIONS_ATTRIBUTE = "Viaduct-Relocations"

internal val commonDependencyRelocations = listOf(
    "com.google.common" to "viaduct.shaded.guava",
    "com.google.guava" to "viaduct.shaded.guava",
    "com.fasterxml.jackson" to "viaduct.shaded.jackson",
    "org.slf4j" to "viaduct.shaded.slf4j",
)

internal fun describeRelocations(relocations: List<Pair<String, String>>): String = relocations.joinToString(",") { (from, to) -> "$from->$to" }

internal fun describeRelocators(relocators: List<Relocator>): String =
    describeRelocations(relocators.map { (it as? SimpleRelocator)?.let { simple -> simple.pattern to simple.shadedPattern } ?: (it.javaClass.name to "?") })

internal fun recordedRelocations(jar: File): String? = JarFile(jar).use { it.manifest?.mainAttributes?.getValue(RELOCATIONS_ATTRIBUTE) }

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

internal fun pomDependencies(pom: File): List<String> =
    XmlParser(false, false).parse(pom).depthFirst().filterIsInstance<Node>().filter { it.name() == "dependency" }.map { dependency ->
        listOf("groupId", "artifactId", "version").joinToString(":") { (dependency.get(it) as NodeList).text() }
    }
