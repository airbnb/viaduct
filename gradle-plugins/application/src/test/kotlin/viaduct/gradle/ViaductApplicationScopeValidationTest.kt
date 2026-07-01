package viaduct.gradle

import io.kotest.matchers.string.shouldContain
import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Smoke coverage of the schema-scoping DSL integration through Gradle TestKit. The purpose here
 * is to prove that the DSL wires end-to-end into a real Gradle build, not to re-cover every error
 * code — per-code coverage lives in `ViaductApplicationExtensionTest` (extension DSL) and
 * `SchemaScopingValidatorTest` (structural validator).
 *
 * The three cases below are the deliberately minimal set:
 *   1. Happy path under `--configuration-cache` — proves the DSL state writer is cache-safe.
 *   2. A representative per-call DSL failure — proves the error surfaces at the user's build
 *      script line, with the error code visible in build output.
 *   3. Cross-property aggregation — proves the multi-error message shape the developer will read.
 */
class ViaductApplicationScopeValidationTest {
    @TempDir
    lateinit var projectDir: File

    private fun writeSettings() {
        File(projectDir, "settings.gradle.kts").writeText("""rootProject.name = "test"""")
    }

    /**
     * Builds a `build.gradle.kts` whose `viaductApplication { ... }` block contains [viaductBlock].
     * The body is line-indented programmatically (rather than via nested `trimIndent` interpolation)
     * so the resulting Kotlin file has consistent indentation regardless of how the caller formats
     * its block.
     */
    private fun buildScript(viaductBlock: String): String =
        buildString {
            appendLine("plugins {")
            appendLine("    `java-library`")
            appendLine("    id(\"com.airbnb.viaduct.application-gradle-plugin\")")
            appendLine("}")
            appendLine()
            appendLine("viaductApplication {")
            appendLine("    modulePackagePrefix.set(\"com.example.test\")")
            viaductBlock.trimIndent().lines().forEach { line ->
                if (line.isBlank()) appendLine() else appendLine("    $line")
            }
            appendLine("}")
        }

    @Test
    fun `valid scope configuration succeeds under configuration cache`() {
        writeSettings()
        File(projectDir, "build.gradle.kts").writeText(
            buildScript(
                """
                declareScoping {
                    scopes("public", "internal")
                    scopedSchema("PUBLIC_API", "public")
                    scopedSchema("INTERNAL_API", "public", "internal")
                }
                """.trimIndent()
            )
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("help", "--configuration-cache", "--configuration-cache-problems=fail")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":help")?.outcome)
    }

    @Test
    fun `malformed scope id surfaces with code and offending id at the DSL line`() {
        writeSettings()
        File(projectDir, "build.gradle.kts").writeText(
            buildScript(
                """
                declareScoping {
                    scopes("BAD-ID")
                }
                """.trimIndent()
            )
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("help")
            .buildAndFail()

        result.output shouldContain "[SCOPE_ID_FORMAT_INVALID]"
        result.output shouldContain "BAD-ID"
        // The stack trace should point at the user's build script line, not at plugin internals.
        result.output shouldContain "build.gradle.kts"
    }

    @Test
    fun `cross-property failures aggregate into a single message with every code`() {
        writeSettings()
        File(projectDir, "build.gradle.kts").writeText(
            buildScript(
                """
                declareScoping {
                    scopes("public")
                    scopedSchema("Alpha", "missing_a")
                    scopedSchema("Beta", "missing_b")
                }
                """.trimIndent()
            )
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("help")
            .buildAndFail()

        result.output shouldContain "viaductApplication declareScoping configuration is invalid"
        // Both findings appear in the same failure message.
        result.output shouldContain "[SCOPED_SCHEMA_UNKNOWN_SCOPE]"
        result.output shouldContain "'Alpha'"
        result.output shouldContain "'Beta'"
        result.output shouldContain "missing_a"
        result.output shouldContain "missing_b"
    }
}
