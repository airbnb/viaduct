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
val baseVersionRaw: String = versionFile.readText().trim().ifEmpty { "0.0.0" }
// Strip any existing -SNAPSHOT suffix to avoid double-suffixing
val baseVersionClean = baseVersionRaw.removeSuffix("-SNAPSHOT")

// Function to get the latest published version from Gradle Plugin Portal
fun getLatestVersionFromPortal(pluginId: String): String? {
    return try {
        // Convert plugin ID to Maven coordinates (com.airbnb.viaduct.module-gradle-plugin -> com/airbnb/viaduct/module-gradle-plugin)
        val mavenPath = pluginId.replace('.', '/')
        val url = java.net.URI("https://plugins.gradle.org/m2/$mavenPath/maven-metadata.xml").toURL()
        val connection = url.openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        if (connection.responseCode == 200) {
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            // Parse XML to find all <version> tags
            val versionRegex = """<version>([^<]+)</version>""".toRegex()
            val matches = versionRegex.findAll(response)
            matches.map { it.groupValues[1] }
                .filter { it.matches(Regex("""\d+\.\d+\.\d+""")) } // Only release versions (X.Y.Z format)
                .maxOfOrNull { version ->
                    val parts = version.split(".")
                    parts[0].toInt() * 10000 + parts[1].toInt() * 100 + parts[2].toInt()
                }?.let { maxVersionInt ->
                    val major = maxVersionInt / 10000
                    val minor = (maxVersionInt % 10000) / 100
                    val patch = maxVersionInt % 100
                    "$major.$minor.$patch"
                }
        } else null
    } catch (e: Exception) {
        logger.warn("Could not fetch version from portal for $pluginId: ${e.message}")
        null
    }
}

fun parseVersion(version: String): Triple<Int, Int, Int> {
    val cleanVersion = version.split("-")[0] // Remove suffixes like "-next"
    val parts = cleanVersion.split(".")
    return Triple(
        parts.getOrNull(0)?.toIntOrNull() ?: 0,
        parts.getOrNull(1)?.toIntOrNull() ?: 0,
        parts.getOrNull(2)?.toIntOrNull() ?: 0
    )
}

val (repoMajor, repoMinor, _) = parseVersion(baseVersionClean)

val env = providers

// Get latest versions from portal for our plugins
val modulePluginId = "com.airbnb.viaduct.module-gradle-plugin"
val appPluginId = "com.airbnb.viaduct.application-gradle-plugin"

val latestModuleVersion = getLatestVersionFromPortal(modulePluginId)
val latestAppVersion = getLatestVersionFromPortal(appPluginId)

logger.lifecycle("Latest module plugin version: ${latestModuleVersion ?: "none"}")
logger.lifecycle("Latest application plugin version: ${latestAppVersion ?: "none"}")

// Check if this is a weekly release or major version release
val isWeeklyRelease = env.environmentVariable("WEEKLY_RELEASE").orElse("false").map { it.toBoolean() }.get()
val isMajorVersionRelease = env.environmentVariable("MAJOR_VERSION_RELEASE").orElse("false").map { it.toBoolean() }.get()
val releaseFlag = env.environmentVariable("RELEASE").orElse("false").map { it.toBoolean() }.get()
val isReleaseTag = env.environmentVariable("GIT_TAG")
    .map { it.matches(Regex("""v\d+\.\d+\.\d+(-[A-Za-z0-9.]+)?""")) }
    .getOrElse(false)

// Check for patch release in commit message
// Use environment variable to avoid running git during configuration phase
val isPatchRelease = try {
    val buildkiteMessage = env.environmentVariable("BUILDKITE_MESSAGE").orNull
    val commitMessage = env.environmentVariable("GIT_COMMIT_MESSAGE").orNull

    listOfNotNull(buildkiteMessage, commitMessage)
        .any { message ->
            message.contains("[VIADUCT]", ignoreCase = true) &&
            message.contains("[PATCH]", ignoreCase = true)
        }
} catch (e: Exception) {
    false
}

// Use VERSION file directly for local builds and CI
// Use the clean version (without -SNAPSHOT suffix) as the base
val baseVersion = baseVersionClean

logger.lifecycle("Using version from VERSION file: $baseVersion (weekly=$isWeeklyRelease, majorRelease=$isMajorVersionRelease, release=$releaseFlag, tag=$isReleaseTag, patch=$isPatchRelease)")

// Check VIADUCT_PLUGIN_SNAPSHOT environment variable to determine snapshot behavior
// When VIADUCT_PLUGIN_SNAPSHOT=false, treat as a release (used for automatic version detection)
val pluginSnapshotEnv = env.environmentVariable("VIADUCT_PLUGIN_SNAPSHOT").orElse("true").map { it.toBoolean() }.get()
val isSnapshot = if (!pluginSnapshotEnv) {
    // VIADUCT_PLUGIN_SNAPSHOT=false means treat as release
    false
} else {
    // Normal logic: only non-snapshot if explicitly marked as release
    listOf(releaseFlag, isReleaseTag, isMajorVersionRelease, isPatchRelease).none { it }
}

val computedVersionStr = if (isSnapshot) {
    "$baseVersion-SNAPSHOT"
} else baseVersion

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
        logger.lifecycle("versionBase=${versionBase.get()}")
        logger.lifecycle("computedVersion=${computedVersion.get()}")
        logger.lifecycle("isSnapshot=${snapshot.get()}")
    }
}

// --- task types ---
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
    @get:Input abstract val computedVersion: Property<String>
    @get:OutputFiles abstract val outputFiles: ConfigurableFileCollection

    @TaskAction fun run() {
        val v = computedVersion.get()
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
        demoappDirs.set(listOf("demoapps/cli-starter", "demoapps/jetty-starter", "demoapps/ktor-starter", "demoapps/starwars"))
        computedVersion.set(computedVersionStr)
        // declare outputs
        outputFiles.setFrom(demoappDirs.get().map { layout.projectDirectory.file("$it/gradle.properties") })
    }

    tasks.register<BumpVersionTask>("bumpVersion") {
        newVersion.set(providers.gradleProperty("newVersion").orElse(
            providers.provider { throw GradleException("Pass -PnewVersion=X.Y.Z") }
        ))
        versionFile.set(layout.projectDirectory.file("VERSION"))
    }

    tasks.register<Exec>("weeklyRelease") {
        group = "publishing"
        description = "Publish a weekly release of Viaduct Gradle plugins"

        commandLine("./gradlew", "gradle-plugins:publishPlugins", "--no-daemon")
        environment("WEEKLY_RELEASE", "true")

        doFirst {
            logger.lifecycle("=== WEEKLY RELEASE ===")
            logger.lifecycle("This will publish a new minor version release to Gradle Plugin Portal")

            // Set environment for weekly release
            System.setProperty("WEEKLY_RELEASE", "true")
        }

        doLast {
            logger.lifecycle("Weekly release completed!")
        }
    }
}
