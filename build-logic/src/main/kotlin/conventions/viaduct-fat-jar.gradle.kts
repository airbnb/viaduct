package conventions

import com.github.jengelman.gradle.plugins.shadow.ShadowStats
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("conventions.kotlin")
    id("com.gradleup.shadow")
    `maven-publish`
    id("conventions.viaduct-publishing")
}

// Fat jars this one layers on: their classes, resources and Maven descriptors stay out of this jar,
// and they become its dependencies.
val fatJarLayers = configurations.dependencyScope("fatJarLayers")
val lowerFatJars = configurations.resolvable("lowerFatJars") {
    extendsFrom(fatJarLayers.get())
    isTransitive = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
    }
}

// Replace the default jar with the shadow jar in all fat-jar publications.
tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    mergeServiceFiles()

    // Drop upstream JAR signing artifacts — relocating bundled classes invalidates them
    // and leaving them in place causes "SecurityException: Invalid signature" at runtime.
    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
    exclude("META-INF/INDEX.LIST")

    // DO NOT exclude META-INF/maven/** — those per-artifact pom.properties files are how
    // external CVE scanners (Trivy, Grype, GitHub dep graph) identify shaded transitive
    // deps inside this fat JAR. Removing them blinds scanners to relocated CVE-bearing
    // libraries and silently invalidates the fine-grained vs fat-JAR parity check in CI.

    val lowerJars: FileCollection = lowerFatJars.get()
    inputs.files(lowerJars).withPropertyName("lowerFatJars").withNormalizer(ClasspathNormalizer::class)
    val shadowRelocators = relocators
    val relocationStats = ShadowStats()
    var lowerPayload: Set<String>? = null
    exclude { element ->
        val payload = lowerPayload ?: layerPayload(lowerJars).also { lowerPayload = it }
        shippedPath(element.path, shadowRelocators, relocationStats) in payload
    }
}

tasks.named<Jar>("jar") {
    enabled = false
}

// Wire the shadow jar into the Gradle variant system so dependency resolution delivers the fat jar,
// and the lower fat jars it layers on, to consumers instead of the (disabled) thin jar.
configurations {
    named("apiElements") {
        extendsFrom(fatJarLayers.get())
        outgoing {
            artifacts.clear()
            artifact(tasks.shadowJar)
        }
    }
    named("runtimeElements") {
        extendsFrom(fatJarLayers.get())
        outgoing {
            artifacts.clear()
            artifact(tasks.shadowJar)
        }
    }
}

// Suppress Gradle module metadata — the standard variants would reference internal viaduct
// coordinates that aren't published individually.
tasks.withType<GenerateModuleMetadata> {
    enabled = false
}

// Replace the published POM's dependencies, which are internal coordinates bundled in this jar,
// with the lower fat jars at this version.
afterEvaluate {
    val lowerArtifacts = fatJarLayers.get().dependencies.map {
        require(it is ProjectDependency) { "fatJarLayers takes only fat-jar projects, not ${it.group}:${it.name}" }
        it.name
    }
    val publishedGroup = project.group.toString()
    val publishedVersion = project.version.toString()
    publishing.publications.withType<MavenPublication>().configureEach {
        pom.withXml {
            val deps = asNode().get("dependencies") as groovy.util.NodeList
            deps.forEach { (it as groovy.util.Node).parent().remove(it) }
            if (lowerArtifacts.isNotEmpty()) {
                val dependencies = asNode().appendNode("dependencies")
                lowerArtifacts.forEach { artifact ->
                    dependencies.appendNode("dependency").apply {
                        appendNode("groupId", publishedGroup)
                        appendNode("artifactId", artifact)
                        appendNode("version", publishedVersion)
                    }
                }
            }
        }
    }
}

tasks.named("assemble") {
    dependsOn(tasks.shadowJar)
}

// Composite-mode collision guard. In an included build, facade consumers receive the transitive
// core jars alongside this project's shadow jar — the POM rewrite (above) that keeps published
// consumers seeing only the fat jars does not apply to composite source substitution. If two core
// modules resolve to the same archive filename (e.g. engine/service/tenant all producing
// `runtime-<version>.jar`), a consumer that flattens this classpath into one directory
// (distTar/distZip/buildLayers) collides. Model that flatten directly and fail fast: Sync every
// runtimeClasspath artifact into a temp dir with DuplicatesStrategy.FAIL.
val checkRuntimeClasspathArtifactNamesAreUnique by tasks.registering(Sync::class) {
    group = "verification"
    description = "Fails if runtimeClasspath contains duplicate artifact filenames."

    from(configurations.named("runtimeClasspath"))
    into(layout.buildDirectory.dir("tmp/checkRuntimeClasspathArtifactNamesAreUnique/lib"))

    duplicatesStrategy = DuplicatesStrategy.FAIL
    includeEmptyDirs = false
}

val checkFatJarLayers by tasks.registering {
    group = "verification"
    description = "Fails if this fat jar ships a class, resource or META-INF/maven descriptor that a lower fat jar ships."
    val fatJar = tasks.shadowJar.flatMap { it.archiveFile }
    val lowerJars: FileCollection = lowerFatJars.get()
    inputs.file(fatJar)
    inputs.files(lowerJars)
    doLast {
        val shipped = layerPayload(listOf(fatJar.get().asFile))
        check(shipped.isNotEmpty()) { "${fatJar.get().asFile.name} ships no classes" }
        lowerJars.forEach { lowerJar ->
            val lowerPayload = layerPayload(listOf(lowerJar))
            check(lowerPayload.isNotEmpty()) { "${lowerJar.name} ships no classes" }
            val shared = shipped.intersect(lowerPayload).sorted()
            check(shared.isEmpty()) { "${fatJar.get().asFile.name} also ships ${shared.size} entries of ${lowerJar.name}, e.g. ${shared.take(5)}" }
        }
    }
}

tasks.named("check") {
    dependsOn(checkRuntimeClasspathArtifactNamesAreUnique, checkFatJarLayers)
}
