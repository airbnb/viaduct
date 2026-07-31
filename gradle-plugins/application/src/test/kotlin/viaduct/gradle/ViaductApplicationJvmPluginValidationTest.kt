package viaduct.gradle

import io.kotest.matchers.string.shouldContain
import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Covers the guardrail that fails configuration when no Java/Kotlin JVM plugin is applied
 * alongside the application plugin. The "api"/"runtimeOnly" wiring in [ViaductApplicationPlugin]
 * is deferred so it works regardless of plugin declaration order, but that means a project which
 * never applies a JVM plugin at all would otherwise configure successfully while silently missing
 * that wiring. This test proves that case still fails loudly instead.
 */
class ViaductApplicationJvmPluginValidationTest {
    @TempDir
    lateinit var projectDir: File

    private fun writeSettings() {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins {
                id("com.airbnb.viaduct.settings-gradle-plugin")
            }

            rootProject.name = "test"

            includeViaductApplication {
                project(":")
                modulePackagePrefix("com.example.test")
            }
            """.trimIndent()
        )
    }

    private fun combinedPluginClasspath(): List<File> =
        System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .map { File(it) }

    @Test
    fun `application plugin without a JVM plugin fails with an actionable error`() {
        writeSettings()
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                id("com.airbnb.viaduct.application-gradle-plugin")
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments("help")
            .buildAndFail()

        result.output shouldContain "no Java/Kotlin JVM plugin"
        result.output shouldContain "com.airbnb.viaduct.application-gradle-plugin"
    }

    @Test
    fun `application plugin with java-library applied first succeeds`() {
        writeSettings()
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
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments("help")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":help")?.outcome)
    }

    @Test
    fun `application plugin with java-library applied after succeeds`() {
        writeSettings()
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                id("com.airbnb.viaduct.application-gradle-plugin")
                `java-library`
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments("help")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":help")?.outcome)
    }
}
