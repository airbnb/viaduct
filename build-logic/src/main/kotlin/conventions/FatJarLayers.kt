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

internal data class Relocation(val from: String, val to: String, val except: List<String> = emptyList())

private val commonDependencyRelocations = listOf(
    Relocation("com.google.common", "viaduct.shaded.guava"),
    Relocation("com.google.guava", "viaduct.shaded.guava"),
    Relocation("com.fasterxml.jackson", "viaduct.shaded.jackson"),
    Relocation("org.slf4j", "viaduct.shaded.slf4j"),
)

enum class FatJarRelocations(
    internal val relocations: List<Relocation>,
    internal val unrelocatedPackages: List<String>?
) {
    COMMON(commonDependencyRelocations, unrelocatedPackages = null),

    // Generated code references graphql-java by name, and the KSP API that the processor implements uses Kotlin types,
    // whose classes reference the JetBrains annotations.
    BUILD_TIME(
        commonDependencyRelocations + listOf(
            Relocation("com.google.thirdparty", "viaduct.shaded.guava.thirdparty"),
            Relocation("com.google.errorprone", "viaduct.shaded.errorprone"),
            Relocation("com.google.j2objc", "viaduct.shaded.j2objc"),
            Relocation("com.google.inject", "viaduct.shaded.guice"),
            Relocation("com.github.benmanes.caffeine", "viaduct.shaded.caffeine"),
            Relocation("com.github.ajalt", "viaduct.shaded.ajalt"),
            Relocation("javassist", "viaduct.shaded.javassist"),
            Relocation("kotlinx.coroutines", "viaduct.shaded.kotlinx.coroutines"),
            Relocation("kotlinx.metadata", "viaduct.shaded.kotlinx.metadata"),
            Relocation("_COROUTINE", "viaduct.shaded._COROUTINE"),
            Relocation("org.antlr", "viaduct.shaded.antlr"),
            Relocation("org.stringtemplate", "viaduct.shaded.stringtemplate"),
            Relocation("org.dataloader", "viaduct.shaded.dataloader"),
            Relocation("org.reactivestreams", "viaduct.shaded.reactivestreams"),
            Relocation("org.checkerframework", "viaduct.shaded.checkerframework"),
            Relocation("org.jspecify", "viaduct.shaded.jspecify"),
            Relocation("org.aopalliance", "viaduct.shaded.aopalliance"),
            Relocation("javax.annotation", "viaduct.shaded.jsr305", except = listOf("javax.annotation.processing.**")),
            Relocation("javax.inject", "viaduct.shaded.javax.inject"),
            Relocation("jakarta.inject", "viaduct.shaded.jakarta.inject"),
        ),
        unrelocatedPackages = listOf("viaduct", "kotlin", "org.jetbrains.annotations", "org.intellij.lang.annotations", "graphql"),
    ),
}

internal fun describeRelocations(relocations: List<Relocation>): String =
    relocations.joinToString(",") { relocation -> "${relocation.from}->${relocation.to}" + relocation.except.map { it.replace('.', '/') }.sorted().joinToString("") { "!$it" } }

internal fun describeRelocators(relocators: List<Relocator>): String =
    describeRelocations(relocators.map { (it as? SimpleRelocator)?.let { simple -> Relocation(simple.pattern, simple.shadedPattern, simple.excludes.toList()) } ?: Relocation(it.javaClass.name, "?") })

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

internal fun unexpectedPackages(
    jar: File,
    allowedPackages: List<String>
): Map<String, Int> =
    ZipFile(jar).use { archive ->
        archive.entries().asSequence().map { it.name.replace(multiReleasePrefix, "") }
            .filter { it.endsWith(".class") && it != "module-info.class" && !it.startsWith("META-INF/") }
            .map { it.substringBeforeLast('/', missingDelimiterValue = "").replace('/', '.') }
            .filterNot { pkg -> allowedPackages.any { pkg == it || pkg.startsWith("$it.") } }
            .groupingBy { it }.eachCount()
    }
