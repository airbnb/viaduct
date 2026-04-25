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

logger.info("Using version from VERSION file: $baseVersion")

gradle.allprojects {
    group = "com.airbnb.viaduct"
    version = baseVersion
}

// ---- Shared demoapp directory list ----

val demoappRelativeDirs = listOf(
    "demoapps/cli-starter",
    "demoapps/jetty-starter",
    "demoapps/ktor-starter",
    "demoapps/micronaut-starter",
    "demoapps/starwars"
)

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
abstract class SetVersionTask : DefaultTask() {
    @get:Input abstract val newVersion: Property<String>
    @get:OutputFile abstract val versionFile: RegularFileProperty

    @TaskAction fun run() {
        require(newVersion.isPresent) { "Pass -PsetVersion=X.Y.Z" }
        val v = newVersion.get()
        requireValidSemver(v)
        versionFile.get().asFile.writeText(v + "\n")
        logger.lifecycle("Wrote VERSION=$v -> ${versionFile.get().asFile}")
    }

    companion object {
        private val SEMVER = Regex("""^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[a-zA-Z0-9-]+(\.[a-zA-Z0-9-]+)*)?$""")
        fun requireValidSemver(version: String) {
            require(!version.contains('+')) { "Version must not contain build metadata (+): $version" }
            require(SEMVER.matches(version)) { "Invalid semver version: $version" }
        }
    }
}

@DisableCachingByDefault(because = "Small file edits, cache not useful")
abstract class SyncDemoAppVersionsTask : DefaultTask() {
    @get:Internal abstract val repoRoot: DirectoryProperty
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

// A validation task must be non-cacheable but still incremental (up-to-date aware).
// @DisableCachingByDefault handles the former; the markerFile output handles the latter.
// Without an output, Gradle treats the task as always out-of-date and reruns it every build.
@DisableCachingByDefault(because = "Validation-only task; outputs only a synthetic marker file")
abstract class ConfirmDemoAppVersionsTask : DefaultTask() {
    // @Internal: repoRoot is only used to resolve inputFiles at execution time; Gradle should
    // not snapshot the whole directory tree for up-to-date checking.
    @get:Internal abstract val repoRoot: DirectoryProperty
    @get:Input abstract val demoappDirs: ListProperty<String>
    @get:Input abstract val expectedVersion: Property<String>
    @get:InputFiles abstract val inputFiles: ConfigurableFileCollection
    // Synthetic marker: written on success so Gradle can skip reruns when inputs haven't changed.
    @get:OutputFile abstract val markerFile: RegularFileProperty

    @TaskAction
    fun run() {
        val expected = expectedVersion.get()
        val root = repoRoot.get().asFile
        val mismatches = mutableListOf<String>()

        demoappDirs.get().forEach { rel ->
            val f = File(root, "$rel/gradle.properties")
            if (!f.exists()) {
                mismatches += "  $rel/gradle.properties: file not found"
                return@forEach
            }
            val props = Properties().also { if (f.exists()) f.inputStream().use(it::load) }
            val actual = props.getProperty("viaductVersion") ?: "<key 'viaductVersion' not found>"
            if (actual != expected) {
                mismatches += "  $rel: expected=$expected actual=$actual"
            }
        }

        if (mismatches.isNotEmpty()) {
            throw GradleException(
                "confirmDemoAppVersions FAILED — version mismatches detected:\n" +
                    mismatches.joinToString("\n") +
                    "\n\nRun ./gradlew syncDemoAppVersions to fix."
            )
        }
        logger.lifecycle("All demoapp versions match VERSION ($expected) ✓")

        markerFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText("ok")
        }
    }
}

@DisableCachingByDefault(because = "Writes a single file")
abstract class BumpSnapshotVersionTask : DefaultTask() {
    @get:OutputFile abstract val versionFile: RegularFileProperty

    @TaskAction
    fun run() {
        val vf = versionFile.get().asFile
        val current = vf.readText().trim()
        SetVersionTask.requireValidSemver(current)
        require(current.endsWith("-SNAPSHOT")) { "VERSION must end with -SNAPSHOT, got: $current" }

        val match = requireNotNull(SNAPSHOT_PATTERN.matchEntire(current)) {
            "VERSION does not match expected SNAPSHOT pattern: $current"
        }

        val base = match.groupValues[1]
        val rest = match.groupValues[3]
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val tag = (1..4).map { chars.random() }.joinToString("")
        val newVersion = "$base-rc.$tag$rest-SNAPSHOT"

        vf.writeText(newVersion + "\n")
        logger.lifecycle("Wrote VERSION: $current -> $newVersion")
    }

    companion object {
        val SNAPSHOT_PATTERN = Regex("""^(\d+\.\d+\.\d+)(-rc\.[a-z0-9]{4})?(.*)-SNAPSHOT$""")
    }
}

@DisableCachingByDefault(because = "Writes a single file")
abstract class UnbumpSnapshotVersionTask : DefaultTask() {
    @get:OutputFile abstract val versionFile: RegularFileProperty

    @TaskAction
    fun run() {
        val vf = versionFile.get().asFile
        val current = vf.readText().trim()
        val match = BumpSnapshotVersionTask.SNAPSHOT_PATTERN.matchEntire(current)
        require(match != null && match.groupValues[2].isNotEmpty()) {
            "VERSION does not contain an -rc.XXXX marker: $current"
        }

        val newVersion = "${match.groupValues[1]}${match.groupValues[3]}-SNAPSHOT"
        vf.writeText(newVersion + "\n")
        logger.lifecycle("Wrote VERSION: $current -> $newVersion")
    }
}

// ---- Task registrations ----

tasks.register<PrintVersionTask>("printVersion") {
    version.set(baseVersion)
}

if (gradle.parent == null) {
    tasks.register<SyncDemoAppVersionsTask>("syncDemoAppVersions") {
        repoRoot.set(layout.projectDirectory)
        demoappDirs.set(demoappRelativeDirs)
        targetVersion.set(providers.provider { layout.projectDirectory.file("VERSION").asFile.readText().trim() })
        outputFiles.setFrom(demoappRelativeDirs.map { layout.projectDirectory.file("$it/gradle.properties") })
        outputs.upToDateWhen { false }
    }

    tasks.register<ConfirmDemoAppVersionsTask>("confirmDemoAppVersions") {
        repoRoot.set(layout.projectDirectory)
        demoappDirs.set(demoappRelativeDirs)
        // Use a lazy provider so the VERSION file is re-read at execution time, consistent with
        // how syncDemoAppVersions reads it. This ensures the correct value is seen even when
        // another task mutates the VERSION file earlier in the same build.
        expectedVersion.set(providers.provider { layout.projectDirectory.file("VERSION").asFile.readText().trim() })
        inputFiles.setFrom(demoappRelativeDirs.map { layout.projectDirectory.file("$it/gradle.properties") })
        markerFile.set(layout.buildDirectory.file("validations/confirmDemoAppVersions.marker"))
    }

    tasks.register<SetVersionTask>("setVersion") {
        newVersion.set(providers.gradleProperty("setVersion"))
        versionFile.set(layout.projectDirectory.file("VERSION"))
        finalizedBy("syncDemoAppVersions")
    }

    tasks.register<BumpSnapshotVersionTask>("bumpSnapshotVersion") {
        versionFile.set(layout.projectDirectory.file("VERSION"))
        outputs.upToDateWhen { false }
        finalizedBy("syncDemoAppVersions")
    }

    tasks.register<UnbumpSnapshotVersionTask>("unbumpSnapshotVersion") {
        versionFile.set(layout.projectDirectory.file("VERSION"))
        outputs.upToDateWhen { false }
        finalizedBy("syncDemoAppVersions")
    }
}
