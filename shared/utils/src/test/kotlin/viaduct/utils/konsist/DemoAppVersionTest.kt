package viaduct.utils.konsist

import java.io.File
import java.util.Properties
import org.junit.jupiter.api.Test

/**
 * Test to ensure that the VERSION file in the OSS root matches the viaductVersion
 * in all demoapp gradle.properties files. This ensures consistent versioning across demoapps.
 */
class DemoAppVersionTest {
    @Test
    fun `demoapp versions should match VERSION file`() {
        // Find the OSS root directory by looking for the VERSION file
        val ossRoot = findOssRoot()
        require(ossRoot != null) { "Could not find OSS root directory with VERSION file" }

        val versionFile = File(ossRoot, "VERSION")
        require(versionFile.exists()) { "VERSION file not found at ${versionFile.absolutePath}" }

        val expectedVersion = versionFile.readText().trim()
        require(expectedVersion.isNotEmpty()) { "VERSION file is empty" }

        // List of demoapps to check
        val demoappDirs = listOf(
            "cli-starter",
            "jetty-starter",
            "micronaut-starter",
            "starwars"
        )

        val mismatches = mutableListOf<String>()

        for (demoappName in demoappDirs) {
            val gradlePropsFile = File(ossRoot, "demoapps/$demoappName/gradle.properties")

            if (!gradlePropsFile.exists()) {
                mismatches.add("$demoappName: gradle.properties file not found")
                continue
            }

            val props = Properties()
            gradlePropsFile.inputStream().use { props.load(it) }

            val demoappVersion = props.getProperty("viaductVersion")

            if (demoappVersion == null) {
                mismatches.add("$demoappName: viaductVersion property not found in gradle.properties")
                continue
            }

            if (demoappVersion.trim() != expectedVersion) {
                mismatches.add(
                    "$demoappName: version mismatch - expected '$expectedVersion' but found '$demoappVersion'"
                )
            }
        }

        if (mismatches.isNotEmpty()) {
            val errorMessage = buildString {
                appendLine("❌ Demoapp version mismatches detected:")
                appendLine("   Expected version from VERSION file: $expectedVersion")
                appendLine()
                mismatches.forEach { mismatch ->
                    appendLine("   - $mismatch")
                }
            }
            throw AssertionError(errorMessage)
        }

        println("✅ All demoapp versions match VERSION file: $expectedVersion")
    }

    /**
     * Find the OSS root directory by looking for the VERSION file.
     * Starts from the current working directory and walks up the directory tree.
     */
    private fun findOssRoot(): File? {
        var currentDir = File(System.getProperty("user.dir"))

        // Walk up the directory tree looking for the VERSION file
        while (currentDir.parentFile != null) {
            val versionFile = File(currentDir, "VERSION")
            val demoappsDir = File(currentDir, "demoapps")

            // Check if this looks like the OSS root (has both VERSION and demoapps)
            if (versionFile.exists() && demoappsDir.exists() && demoappsDir.isDirectory) {
                return currentDir
            }

            currentDir = currentDir.parentFile
        }

        return null
    }
}
