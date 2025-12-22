package buildroot

import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.register

plugins { /* no-op plugin, just conventions */ }

// ---- Version from VERSION file ----

fun findVersionFile(start: File): File {
    var d: File? = start
    while (d != null) {
        val f = File(d, "VERSION")
        if (f.exists()) return f
        d = d.parentFile
    }
    error("Could not find VERSION file starting from: $start")
}

val versionFile = findVersionFile(rootDir)
val baseVersion: String = versionFile.readText().trim().ifEmpty { "0.0.0" }

logger.lifecycle("Using version from VERSION file: $baseVersion")

gradle.allprojects {
    group = "com.airbnb.viaduct"
    version = baseVersion
}

// --- task types ---

@DisableCachingByDefault(because = "Just prints to console")
abstract class PrintVersionTask : DefaultTask() {
    @get:Input abstract val version: Property<String>

    @TaskAction
    fun run() {
        logger.lifecycle("version=${version.get()}")
    }
}

@DisableCachingByDefault(because = "Writes a single file")
abstract class BumpVersionTask : DefaultTask() {
    @get:Input abstract val newVersion: Property<String>
    @get:OutputFile abstract val versionFile: RegularFileProperty
    @TaskAction fun run() {
        versionFile.get().asFile.writeText(newVersion.get() + "\n")
        logger.lifecycle("Wrote VERSION=${newVersion.get()} -> ${versionFile.get().asFile}")
    }
}

@DisableCachingByDefault(because = "Small file edits, cache not useful")
abstract class SyncDemoAppVersionsTask : DefaultTask() {
    @get:InputDirectory abstract val repoRoot: DirectoryProperty
    @get:Input abstract val demoappDirs: ListProperty<String>
    @get:Input abstract val targetVersion: Property<String>
    @get:OutputFiles abstract val outputFiles: ConfigurableFileCollection

    @TaskAction fun run() {
        val v = targetVersion.get()
        val root = repoRoot.get().asFile

        demoappDirs.get().forEach { rel ->
            val f = File(root, "$rel/gradle.properties")
            val props = Properties().also { if (f.exists()) f.inputStream().use(it::load) }
            props["viaductVersion"] = v

            val ordered = props.entries.map { it.key.toString() to it.value.toString() }.sortedBy { it.first }
            f.parentFile.mkdirs()
            f.writeText(ordered.joinToString(System.lineSeparator()) { (k, x) -> "$k=$x" } + System.lineSeparator())
            logger.lifecycle("Updated ${f.relativeTo(root)} -> $v")
        }
    }
}

// ---- Task registrations ----

tasks.register<PrintVersionTask>("printVersion") {
    version.set(baseVersion)
}

if (gradle.parent == null) {
    tasks.register<SyncDemoAppVersionsTask>("syncDemoAppVersions") {
        repoRoot.set(layout.projectDirectory)
        demoappDirs.set(listOf("demoapps/cli-starter", "demoapps/jetty-starter", "demoapps/ktor-starter", "demoapps/micronaut-starter", "demoapps/starwars"))
        targetVersion.set(baseVersion)
        outputFiles.setFrom(demoappDirs.get().map { layout.projectDirectory.file("$it/gradle.properties") })
    }

    tasks.register<BumpVersionTask>("bumpVersion") {
        newVersion.set(providers.gradleProperty("newVersion").orElse(
            providers.provider { throw GradleException("Pass -PnewVersion=X.Y.Z") }
        ))
        versionFile.set(layout.projectDirectory.file("VERSION"))
    }
}
