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

@DisableCachingByDefault(because = "Reads git state and writes a file — branch-dependent")
abstract class SetReleaseCandidateVersionTask : DefaultTask() {
    @get:Input abstract val rcNumber: Property<Int>
    @get:Internal abstract val repoRoot: DirectoryProperty
    @get:OutputFile abstract val versionFile: RegularFileProperty

    @TaskAction
    fun run() {
        val root = repoRoot.get().asFile

        // 1. Detect current branch via git
        val gitProc = ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
            .directory(root)
            .redirectErrorStream(true)
            .start()
        val gitOutput = gitProc.inputStream.bufferedReader().readText().trim()
        val gitExit = gitProc.waitFor()
        if (gitExit != 0 || gitOutput.isEmpty()) {
            throw GradleException("Could not determine current git branch (exit=$gitExit):\n$gitOutput")
        }
        val branch = gitOutput.lines().first().trim()

        // 2. Validate branch matches release/vX.Y.Z
        val branchPattern = Regex("""^release/v(\d+\.\d+\.\d+)$""")
        val match = branchPattern.matchEntire(branch)
            ?: throw GradleException(
                "setReleaseCandidateVersion must be run on a release/vX.Y.Z branch.\n" +
                    "  Current branch: $branch"
            )
        val baseVersion = match.groupValues[1]

        // 3. Validate VERSION file starts with X.Y.Z from branch
        val vf = versionFile.get().asFile
        val currentVersion = vf.readText().trim()
        if (currentVersion != baseVersion && !currentVersion.startsWith("$baseVersion-")) {
            throw GradleException(
                "VERSION mismatch: branch is release/v$baseVersion " +
                    "but VERSION contains '$currentVersion'.\n" +
                    "  Expected VERSION to be '$baseVersion' or '$baseVersion-*'."
            )
        }

        // 4. Write X.Y.Z-rc.<n>-SNAPSHOT
        val rc = rcNumber.get()
        val newVersion = "$baseVersion-rc.$rc-SNAPSHOT"
        vf.writeText(newVersion + "\n")
        logger.lifecycle("Wrote VERSION: $currentVersion -> $newVersion")

        // 5. Demoapp gradle.properties are synced by syncDemoAppVersions (wired via finalizedBy)
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
        // setReleaseCandidateVersion mutates the VERSION file earlier in the same build.
        expectedVersion.set(providers.provider { layout.projectDirectory.file("VERSION").asFile.readText().trim() })
        inputFiles.setFrom(demoappRelativeDirs.map { layout.projectDirectory.file("$it/gradle.properties") })
        markerFile.set(layout.buildDirectory.file("validations/confirmDemoAppVersions.marker"))
    }

    tasks.register<SetReleaseCandidateVersionTask>("setReleaseCandidateVersion") {
        rcNumber.set(providers.gradleProperty("rcNumber").map { it.toInt() }.orElse(1))
        repoRoot.set(layout.projectDirectory)
        versionFile.set(layout.projectDirectory.file("VERSION"))
        finalizedBy("syncDemoAppVersions")
    }

    tasks.register<BumpVersionTask>("bumpVersion") {
        newVersion.set(providers.gradleProperty("newVersion").orElse(
            providers.provider { throw GradleException("Pass -PnewVersion=X.Y.Z") }
        ))
        versionFile.set(layout.projectDirectory.file("VERSION"))
    }
}
