package viaduct.gradle

import io.kotest.matchers.string.shouldContain
import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * End-to-end coverage of the schema-scoping configuration diagnostics, exercised through Gradle
 * TestKit so the user-facing failure surface (the text the developer sees in `gradle build` output)
 * is what the test asserts on. Pure unit coverage lives in `SchemaScopingValidatorTest`.
 *
 * The cases here intentionally stay narrow:
 * - one happy path under `--configuration-cache` (with `--configuration-cache-problems=fail`) to
 *   lock the `afterEvaluate` hook into being cache-safe without coupling the assertion to Gradle's
 *   internal log strings;
 * - one per-ID failure to confirm the setter-time path renders the offending id and code;
 * - one cross-property failure (also under `--configuration-cache`) that aggregates multiple
 *   findings into a single failure message — the failing-path counterpart to the happy-path
 *   cache check.
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
                declaredSchemaScopes(setOf("public", "internal"))
                declaredScopedSchemas(
                    "PUBLIC_API" to setOf("public"),
                    "INTERNAL_API" to setOf("public", "internal"),
                )
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
            buildScript("""declaredSchemaScopes(setOf("BAD-ID"))""")
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
                declaredSchemaScopes(setOf("public"))
                declaredScopedSchemas(
                    "Alpha" to setOf("missing_a"),
                    "Beta" to setOf("missing_b"),
                )
                """.trimIndent()
            )
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("help", "--configuration-cache", "--configuration-cache-problems=fail")
            .buildAndFail()

        result.output shouldContain "viaductApplication scope configuration is invalid"
        // Both findings appear in the same failure message.
        result.output shouldContain "[SCOPED_SCHEMA_UNKNOWN_SCOPE]"
        result.output shouldContain "'Alpha'"
        result.output shouldContain "'Beta'"
        result.output shouldContain "missing_a"
        result.output shouldContain "missing_b"
    }
}
