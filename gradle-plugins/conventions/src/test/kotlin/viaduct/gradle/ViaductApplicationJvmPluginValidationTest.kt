package viaduct.gradle

import io.kotest.matchers.string.shouldContain
import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

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
                usePlugin("com.airbnb.viaduct.kotlinapi")
                usePlugin("com.airbnb.viaduct.javaapi")
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
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments("help")
            .buildAndFail()

        result.output shouldContain "requires a Java/Kotlin JVM plugin"
        result.output shouldContain "Viaduct language integration"
    }

    @Test
    fun `application plugin with java-library applied first succeeds`() {
        writeSettings()
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
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
