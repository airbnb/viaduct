package viaduct.gradle

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * End-to-end coverage of the slice-4 build-time gating of the `@scope` directive.
 *
 * Behavior under test:
 * - When `viaductApplication.declareScoping { scopes(...) }` is present, `AssembleCentralSchemaTask`
 *   asks `DefaultSchemaFactory` to emit the `@scope` directive definition and to decorate the
 *   framework's own types (Node, PageInfo, synthetic root types) with `@scope(to: ["*"])`.
 * - When scoping is not declared, none of those decorations are emitted — and a tenant schema
 *   that still uses `@scope` fails assembly with a Viaduct-flavored diagnostic that names the
 *   `declareScoping` DSL as the fix.
 */
class ViaductApplicationScopeDirectiveGatingTest : ViaductApplicationTestKitFixture() {
    private fun builtInSchema(): String = File(projectDir, "build/viaduct/centralSchema/BUILTIN_SCHEMA.graphqls").readText()

    @Test
    fun `built-in schema contains @scope directive when scoping is declared`() {
        writeSettings()
        File(projectDir, "build.gradle.kts").writeText(
            buildScript(
                """
                declareScoping {
                    scopes("public")
                }
                """.trimIndent()
            )
        )
        writeSchemaFile(
            "src/viaduct/schema/types.graphqls",
            """
            extend type Query @scope(to: ["public"]) {
                hello: String
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments("assembleViaductCentralSchema", "--stacktrace")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":assembleViaductCentralSchema")?.outcome)
        val sdl = builtInSchema()
        sdl shouldContain "directive @scope"
        sdl shouldContain "@scope(to: [\"*\"])"
    }

    @Test
    fun `built-in schema omits @scope directive when scoping is not declared`() {
        writeSettings()
        File(projectDir, "build.gradle.kts").writeText(
            buildScript("") // no declareScoping call at all
        )
        writeSchemaFile(
            "src/viaduct/schema/types.graphqls",
            """
            extend type Query {
                hello: String
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments("assembleViaductCentralSchema", "--stacktrace")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":assembleViaductCentralSchema")?.outcome)
        val sdl = builtInSchema()
        sdl shouldNotContain "directive @scope"
        sdl shouldNotContain "@scope("
    }

    @Test
    fun `@scope usage without declareScoping fails with Viaduct-flavored guidance`() {
        writeSettings()
        File(projectDir, "build.gradle.kts").writeText(
            buildScript("") // scoping NOT declared, but tenant SDL still uses @scope
        )
        writeSchemaFile(
            "src/viaduct/schema/types.graphqls",
            """
            extend type Query @scope(to: ["public"]) {
                hello: String
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments("assembleViaductCentralSchema", "--stacktrace")
            .buildAndFail()

        result.output shouldContain "declareScoping"
        result.output shouldContain "viaductApplication"
        result.output shouldContain "scope"
    }

    // Regression pin for the reviewer's finding: the opt-in signal for `@scope` emission must be
    // "the app declared a scope universe" (`SchemaScoping.scopeUniverse.isNotEmpty()`), NOT
    // "the app registered at least one scoped-schema selection". A scoped application is allowed
    // to register zero scoped schemas (per #361), so an implementation that treats the
    // scoped-schema count as the opt-in incorrectly drops `directive @scope` for that valid case.
    @Test
    fun `built-in schema still emits @scope when universe is declared but zero scoped schemas are registered`() {
        writeSettings()
        File(projectDir, "build.gradle.kts").writeText(
            buildScript(
                """
                declareScoping {
                    // Universe declared, but no declareScopedSchema(...) call — matches the
                    // reviewer-reproduced case of a scoped app that intentionally registers
                    // zero scoped-schema selections at this point in the build.
                    scopes("public")
                }
                """.trimIndent()
            )
        )
        writeSchemaFile(
            "src/viaduct/schema/types.graphqls",
            """
            extend type Query @scope(to: ["public"]) {
                hello: String
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments("assembleViaductCentralSchema", "--stacktrace")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":assembleViaductCentralSchema")?.outcome)
        val sdl = builtInSchema()
        sdl shouldContain "directive @scope"
        sdl shouldContain "@scope(to: [\"*\"])"
    }

    @Test
    fun `validateViaductSchemaExtensions surfaces the same @scope guidance without declareScoping`() {
        writeSettings()
        File(projectDir, "build.gradle.kts").writeText(
            buildScript("") // scoping NOT declared
        )
        writeSchemaFile(
            "src/viaduct/schema/types.graphqls",
            """
            extend type Query @scope(to: ["public"]) {
                hello: String
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments("validateViaductSchemaExtensions", "--stacktrace")
            .buildAndFail()

        // Preflight must gate on `isScoped` identically to assembly. If it doesn't, the preflight
        // silently accepts SDL that assembly would reject, which is the finding #2 regression.
        result.output shouldContain "declareScoping"
        result.output shouldContain "viaductApplication"
        result.output shouldContain "scope"
    }
}
