package viaduct.gradle

import io.kotest.matchers.string.shouldContain
import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * End-to-end coverage of the SDL-layer schema-scoping rule (ScopeUsageRule) as it surfaces through
 * Gradle TestKit. Pure rule logic lives in `ScopeUsageRuleTest` over a hand-built ViaductSchema;
 * the cases here exist to lock in the *wiring path*:
 *
 * - the `viaductApplication.declareScoping { scopes(...) }` universe flows through
 *   `appExt.schemaScoping` into `AssembleCentralSchemaTask.schemaScoping`,
 * - `DefaultSchemaValidator.create(validScopes)` is the construction call the assemble task makes
 *   when the universe is non-empty,
 * - a @scope typo in user SDL surfaces as a build failure with the stable error code, even when
 *   no `scopedSchema(...)` entry is a proper subset of the universe (the spec-gap scenario
 *   that motivated moving validation out of `ScopeDirectiveParser` and into a primary phase).
 *
 * Both cases run under `--configuration-cache --configuration-cache-problems=fail` so that the
 * Property<SetProperty<String>> wiring stays cache-safe.
 */
class ViaductApplicationSchemaScopeRuleTest : ViaductApplicationTestKitFixture() {
    @Test
    fun `assembleViaductCentralSchema fails with SCOPE_NAME_NOT_DECLARED when SDL uses a scope outside the declared universe`() {
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
            extend type Query @scope(to: ["typo"]) {
                widget: String
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments("assembleViaductCentralSchema", "--stacktrace")
            .buildAndFail()

        result.output shouldContain "[SCOPE_NAME_NOT_DECLARED]"
        result.output shouldContain "typo"
        // The configured universe should appear in the error so the user knows what was declared.
        result.output shouldContain "public"
    }

    @Test
    fun `assembleViaductCentralSchema succeeds with a clean scope universe under configuration cache`() {
        writeSettings()
        File(projectDir, "build.gradle.kts").writeText(
            buildScript(
                """
                declareScoping {
                    scopes("public", "internal")
                }
                """.trimIndent()
            )
        )
        // The framework injects the base Query type and a Node interface (both with @scope(["*"])),
        // and a Query extension carrying node/nodes resolvers (also @scope(["*"])). A user adds
        // their own Query extension narrowing to a declared scope.
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
            .withArguments(
                "assembleViaductCentralSchema",
                "--configuration-cache",
                "--configuration-cache-problems=fail",
                "--stacktrace"
            )
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":assembleViaductCentralSchema")?.outcome)
    }

    @Test
    fun `assembleViaductCentralSchema is unaffected when no scope universe is declared`() {
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
            .withArguments(
                "assembleViaductCentralSchema",
                "--configuration-cache",
                "--configuration-cache-problems=fail"
            )
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":assembleViaductCentralSchema")?.outcome)
    }
}
