package viaduct.gradle

import io.kotest.matchers.string.shouldContain
import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ViaductApplicationTopologyValidationTest {
    @TempDir
    lateinit var projectDir: File

    @Test
    fun `application project declared in topology configures successfully`() {
        writeSettings(
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
        writeBuildScript(
            """
            plugins {
                `java-library`
                id("com.airbnb.viaduct.application-gradle-plugin")
            }
            """.trimIndent()
        )

        val result = runner()
            .withArguments("help")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":help")?.outcome)
    }

    @Test
    fun `application plugin fails when settings topology service is absent`() {
        writeSettings("""rootProject.name = "test"""")
        writeBuildScript(
            """
            plugins {
                `java-library`
                id("com.airbnb.viaduct.application-gradle-plugin")
            }
            """.trimIndent()
        )

        val result = runner()
            .withArguments("help")
            .buildAndFail()

        result.output shouldContain "Viaduct settings topology is required"
        result.output shouldContain "com.airbnb.viaduct.settings-gradle-plugin"
    }

    @Test
    fun `application plugin fails when project is declared only as a module`() {
        writeSettings(
            """
            plugins {
                id("com.airbnb.viaduct.settings-gradle-plugin")
            }

            rootProject.name = "test"

            includeViaductApplication {
                project(":app")
                modulePackagePrefix("com.example.test")

                includeModule {
                    project(":app:resolvers")
                    modulePackageSuffix("resolvers")
                }
            }
            """.trimIndent()
        )
        File(projectDir, "app/resolvers").mkdirs()
        File(projectDir, "app/resolvers/build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                id("com.airbnb.viaduct.application-gradle-plugin")
            }
            """.trimIndent()
        )

        val result = runner()
            .withArguments(":app:resolvers:help")
            .buildAndFail()

        result.output shouldContain ":app:resolvers"
        result.output shouldContain "declares it as a module"
        result.output shouldContain ":app"
    }

    @Test
    fun `application plugin fails with underlying malformed topology service error`() {
        writeSettings(
            """
            plugins {
                id("com.airbnb.viaduct.settings-gradle-plugin")
            }

            rootProject.name = "test"

            includeViaductApplication {
                project(":")
                modulePackagePrefix("com.example.test")
            }

            gradle.settingsEvaluated {
                val registration = gradle.sharedServices.registrations
                    .named("ViaductTopologyService")
                    .get()
                val topologyJson = registration.parameters.javaClass
                    .getMethod("getTopologyJson")
                    .invoke(registration.parameters) as org.gradle.api.provider.Property<String>
                topologyJson.set("{")
            }
            """.trimIndent()
        )
        writeBuildScript(
            """
            plugins {
                `java-library`
                id("com.airbnb.viaduct.application-gradle-plugin")
            }
            """.trimIndent()
        )

        val result = runner()
            .withArguments("help")
            .buildAndFail()

        result.output shouldContain "Failed to apply plugin 'com.airbnb.viaduct.application-gradle-plugin'"
        result.output shouldContain "Unexpected end-of-input"
    }

    private fun writeSettings(content: String) {
        File(projectDir, "settings.gradle.kts").writeText(content)
    }

    private fun writeBuildScript(content: String) {
        File(projectDir, "build.gradle.kts").writeText(content)
    }

    private fun combinedPluginClasspath(): List<File> =
        System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .map { File(it) }

    private fun runner(): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
}
