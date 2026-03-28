package viaduct.gradle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir

/**
 * TestKit functional tests for the application plugin.
 *
 * These tests cover diagnostics and configuration-time behavior only.
 * Real execution (codegen, serve) is validated through demoapps.
 */
class ViaductApplicationPluginFunctionalTest {
    @TempDir
    lateinit var projectDir: File

    @Test
    fun `invalid serve dot port property value fails with clear message`() {
        File(projectDir, "settings.gradle.kts").writeText("""rootProject.name = "test-app"""")
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                id("com.airbnb.viaduct.application-gradle-plugin")
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("help", "-Pserve.port=notanumber")
            .buildAndFail()

        assertTrue(result.output.contains("serve.port"), "Expected output to mention 'serve.port'")
        assertTrue(result.output.contains("notanumber"), "Expected output to mention 'notanumber'")
    }
}
