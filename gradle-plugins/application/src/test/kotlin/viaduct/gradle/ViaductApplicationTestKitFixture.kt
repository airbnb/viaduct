package viaduct.gradle

import java.io.File
import org.junit.jupiter.api.io.TempDir

/**
 * Shared TestKit boilerplate for exercising the Viaduct application plugin end-to-end.
 *
 * The three helpers below (`writeSettings`, `buildScript`, `writeSchemaFile`) were previously
 * copy-pasted across every scope-related TestKit class. They are just the mechanical setup for a
 * `viaductApplication { ... }` project — nothing test-specific — so subclasses only carry the
 * DSL block and assertions they actually differ on.
 *
 * `@TempDir` on `projectDir` is honored by JUnit 5 on inherited fields, so subclasses do not need
 * to redeclare it.
 */
abstract class ViaductApplicationTestKitFixture {
    @TempDir
    lateinit var projectDir: File

    protected fun writeSettings() {
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

    protected fun combinedPluginClasspath(): List<File> =
        System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .map { File(it) }

    /**
     * Builds a `build.gradle.kts` whose `viaductApplication { ... }` block contains [viaductBlock].
     * The body is line-indented programmatically (rather than via nested `trimIndent` interpolation)
     * so the resulting Kotlin file has consistent indentation regardless of how the caller formats
     * its block.
     */
    protected fun buildScript(viaductBlock: String): String =
        buildString {
            appendLine("plugins {")
            appendLine("    `java-library`")
            appendLine("    id(\"com.airbnb.viaduct.application-gradle-plugin\")")
            appendLine("}")
            appendLine()
            appendLine("viaductApplication {")
            viaductBlock.trimIndent().lines().forEach { line ->
                if (line.isBlank()) appendLine() else appendLine("    $line")
            }
            appendLine("}")
        }

    protected fun writeSchemaFile(
        relativePath: String,
        sdl: String
    ) {
        val file = File(projectDir, relativePath)
        file.parentFile.mkdirs()
        file.writeText(sdl)
    }
}
