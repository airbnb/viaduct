package viaduct.gradle

import io.kotest.matchers.string.shouldContain
import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Tests for KSP-related validation in ViaductModulePlugin.
 *
 * Covers:
 * - Kotlin version range validation (unit tests on static function)
 * - KSP version mismatch detection (unit tests on static function)
 * - KSP-absent error (TestKit)
 */
class ViaductModulePluginKspValidationTest {
    // ── Kotlin version range validation ─────────────────────────────────────

    @Test
    fun `Kotlin 1_9_24 is accepted`() {
        assertNull(ViaductModulePlugin.validateKotlinVersion("1.9.24"))
    }

    @Test
    fun `Kotlin 2_0_21 is accepted`() {
        assertNull(ViaductModulePlugin.validateKotlinVersion("2.0.21"))
    }

    @Test
    fun `Kotlin 2_1_20 is accepted`() {
        assertNull(ViaductModulePlugin.validateKotlinVersion("2.1.20"))
    }

    @Test
    fun `Kotlin 2_2_21 is accepted`() {
        assertNull(ViaductModulePlugin.validateKotlinVersion("2.2.21"))
    }

    @Test
    fun `Kotlin 1_8_22 is rejected`() {
        val error = ViaductModulePlugin.validateKotlinVersion("1.8.22")
        error!! shouldContain "[1.9, 2.2]"
        error shouldContain "1.8.22"
    }

    @Test
    fun `Kotlin 2_3_0 is rejected`() {
        val error = ViaductModulePlugin.validateKotlinVersion("2.3.0")
        error!! shouldContain "[1.9, 2.2]"
        error shouldContain "2.3.0"
        error shouldContain "KSP2"
    }

    @Test
    fun `Kotlin 3_0_0 is rejected`() {
        val error = ViaductModulePlugin.validateKotlinVersion("3.0.0")
        error!! shouldContain "[1.9, 2.2]"
    }

    @Test
    fun `unparseable version returns null (no error)`() {
        assertNull(ViaductModulePlugin.validateKotlinVersion("not-a-version"))
    }

    // ── KSP-absent error (TestKit) ───────────────────────────────────────────

    @TempDir
    lateinit var projectDir: File

    private fun combinedPluginClasspath(): List<File> =
        System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .map { File(it) }

    @Test
    fun `module without KSP fails with actionable error`() {
        File(projectDir, "settings.gradle.kts").writeViaductSettings(modules = mapOf(":" to "test"))
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                id("com.airbnb.viaduct.application-gradle-plugin")
                id("com.airbnb.viaduct.module-gradle-plugin")
            }
            viaductApplication {
                modulePackagePrefix.set("com.example.test")
            }
            viaductModule {
                modulePackageSuffix.set("test")
            }
            """.trimIndent()
        )
        val schemaDir = File(projectDir, "src/main/viaduct/schema").also { it.mkdirs() }
        File(schemaDir, "schema.graphqls").writeText("type Query { field: String }")

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments("help")
            .buildAndFail()

        result.output shouldContain "BUILD FAILED"
        result.output shouldContain "com.google.devtools.ksp"
    }
}
