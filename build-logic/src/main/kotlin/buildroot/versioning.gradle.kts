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

// ---- Version computation at configuration time (ok for CC) ----

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

val env = providers
val isReleaseTag = env.environmentVariable("GIT_TAG")
    .map { it.matches(Regex("""v\d+\.\d+\.\d+(-[A-Za-z0-9.]+)?""")) }
    .getOrElse(false)

val releaseFlag = env.environmentVariable("RELEASE").orElse("false").map { it.toBoolean() }.get()
val isSnapshot = !(releaseFlag || isReleaseTag)
val computedVersionStr = if (isSnapshot) "$baseVersion-SNAPSHOT" else baseVersion

gradle.allprojects {
    group = "com.airbnb.viaduct"
    version = computedVersionStr
    extensions.extraProperties["versionBase"] = baseVersion
    extensions.extraProperties["versionIsSnapshot"] = isSnapshot
}

@DisableCachingByDefault(because = "Just prints to console")
abstract class PrintVersionTask : DefaultTask() {
    @get:Input abstract val versionBase: Property<String>
    @get:Input abstract val computedVersion: Property<String>
    @get:Input abstract val snapshot: Property<Boolean>

    @TaskAction
    fun run() {
        println("versionBase=${versionBase.get()}")
        println("computedVersion=${computedVersion.get()}")
        println("isSnapshot=${snapshot.get()}")
    }
}

// --- task types ---
@DisableCachingByDefault(because = "Writes a single file")
abstract class BumpVersionTask : DefaultTask() {
    @get:Input abstract val newVersion: Property<String>
    @get:OutputFile abstract val versionFile: RegularFileProperty
    @TaskAction fun run() {
        versionFile.get().asFile.writeText(newVersion.get() + "\n")
        println("Wrote VERSION=${newVersion.get()} -> ${versionFile.get().asFile}")
    }
}

@DisableCachingByDefault(because = "Small file edits, cache not useful")
abstract class SyncDemoAppVersionsTask : DefaultTask() {
    @get:InputDirectory abstract val repoRoot: DirectoryProperty
    @get:Input abstract val demoappDirs: ListProperty<String>
    @get:InputFile abstract val versionFile: RegularFileProperty
    @get:OutputFiles abstract val outputFiles: ConfigurableFileCollection

    @TaskAction fun run() {
        val v = versionFile.get().asFile.readText().trim()
        val root = repoRoot.get().asFile

        demoappDirs.get().forEach { rel ->
            val f = File(root, "$rel/gradle.properties")
            val props = Properties().also { if (f.exists()) f.inputStream().use(it::load) }
            props["viaductVersion"] = v

            val ordered = props.entries.map { it.key.toString() to it.value.toString() }.sortedBy { it.first }
            f.parentFile.mkdirs()
            f.writeText(ordered.joinToString(System.lineSeparator()) { (k, x) -> "$k=$x" } + System.lineSeparator())
            println("Updated ${f.relativeTo(root)} -> $v")
        }
    }
}

// ---- Registrations (root only for bump/sync) ----

tasks.register<PrintVersionTask>("printVersion") {
    versionBase.set(baseVersion)
    computedVersion.set(computedVersionStr)
    snapshot.set(isSnapshot)
}

if (gradle.parent == null) {
    // Extension to configure which demoapps to touch
    abstract class DemoappsSyncExtension {
        abstract val demoappDirs: ListProperty<String>
    }

    // --- registrations (root only) ---

    tasks.register<SyncDemoAppVersionsTask>("syncDemoAppVersions") {
        repoRoot.set(layout.projectDirectory)
        demoappDirs.set(listOf("demoapps/cli-starter", "demoapps/starwars", "demoapps/spring-starter"))
        versionFile.set(layout.projectDirectory.file("VERSION"))
        // declare outputs
        outputFiles.setFrom(demoappDirs.get().map { layout.projectDirectory.file("$it/gradle.properties") })
    }

    tasks.register<BumpVersionTask>("bumpVersion") {
        newVersion.set(providers.gradleProperty("newVersion").orElse(
            providers.provider { throw GradleException("Pass -PnewVersion=X.Y.Z") }
        ))
        versionFile.set(layout.projectDirectory.file("VERSION"))
    }
}
