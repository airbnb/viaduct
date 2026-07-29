package viaduct.gradle

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * End-to-end coverage of slice-5 scoped-schema materialization inside `AssembleCentralSchemaTask`.
 *
 * Slice 5 wires `ScopedSchemaValidator` into the assembly path: for every scoped app, the task
 * enumerates the distinct scope sets to materialize (the union of declared `scopedSchemas.values`
 * and the always-included `scopeUniverse`), builds each filtered projection via
 * `ScopedSchemaBuilder`, runs `IntrospectionQuery` against it, and fails the build with a
 * Viaduct-flavored diagnostic if any set fails to introspect.
 *
 * All SDL fixtures here use type-level `@scope` only. The framework's `@scope` directive is
 * defined on OBJECT | INPUT_OBJECT | ENUM | INTERFACE | UNION — NOT `FIELD_DEFINITION` — so per-
 * field `@scope` is a schema-syntax error and would never reach the slice-5 materialization
 * stage. Keeping fixtures type-level-only guarantees the failure mode we assert here is the one
 * we actually shipped.
 */
class ViaductApplicationScopedSchemaMaterializationTest : ViaductApplicationTestKitFixture() {
    // Scenario 5 — the empty-set alias (`scopedSchema("FULL_ALIAS")` with no scope IDs) must NOT
    // silently short-circuit. Under our chosen deviation from the spec's literal
    // `if (scopeSet.isEmpty()) continue`, empty aliases resolve into the always-added universe
    // set — we prove this here by asserting the INFO line for the universe set appears even when
    // the ONLY declared alias is the empty one.
    @Test
    fun `empty-set alias is validated as the universe scope set, not skipped`() {
        writeSettings()
        File(projectDir, "build.gradle.kts").writeText(
            buildScript(
                """
                declareScoping {
                    scopes("public")
                    scopedSchema("FULL_ALIAS")
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
            .withArguments("assembleViaductCentralSchema", "--info", "--stacktrace")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":assembleViaductCentralSchema")?.outcome)
        val universeLines = result.output.lines().filter {
            it.contains("Validated scoped schema") && it.contains("[public]")
        }
        assertTrue(universeLines.isNotEmpty()) {
            "expected an INFO line for scope set [public] (the universe, resolved from the " +
                "empty-set alias); got:\n${result.output}"
        }
    }

    // Scenario 6 — failure diagnostics must identify the scope set, not any alias the app chose.
    // The scope set is the load-bearing identity for validation; alias names are UI sugar and
    // could rename or collide without changing what the app actually exposes. A diagnostic that
    // says "scoped schema `MY_ALIAS` failed" instead of naming the scope set forces operators to
    // grep the build script to figure out what actually broke.
    @Test
    fun `failure diagnostic names the scope set, not the alias`() {
        writeSettings()
        File(projectDir, "build.gradle.kts").writeText(
            buildScript(
                """
                declareScoping {
                    scopes("public", "phantom")
                    scopedSchema("MY_ALIAS", "phantom")
                }
                """.trimIndent()
            )
        )
        // Query is only in the "public" scope. Applying {phantom} filters Query away and the
        // filtered schema has no Query root — introspection fails for scope set [phantom].
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

        result.output shouldContain "phantom"
        result.output shouldContain "SCOPED_SCHEMA_INTROSPECTION_FAILED"
        // MY_ALIAS is a user-chosen label with no validation identity; pinning that it stays out
        // of the diagnostic prevents a future refactor from leaking alias strings back in.
        result.output shouldNotContain "MY_ALIAS"
    }

    // Scenario 7 — pins the D3 deviation. A scoped app is allowed to register zero explicit
    // scoped schemas (per the PR #401 regression: `built-in schema still emits @scope when
    // universe is declared but zero scoped schemas are registered`). Slice 5 must still perform
    // materialization for the always-added universe set in that config — otherwise a scoped app
    // with zero declared scoped schemas would have NO build-time introspection check at all, and
    // a scoped runtime `SchemaId.Base` projection could ship broken.
    //
    // Positive-path assertion: valid tenant SDL + zero scoped schemas → build succeeds AND the
    // INFO log confirms the universe set was materialized (proving slice 5 ran, not just that
    // slice 5 was skipped).
    @Test
    fun `scoped app with zero declared scoped schemas still materializes the universe set`() {
        writeSettings()
        File(projectDir, "build.gradle.kts").writeText(
            buildScript(
                """
                declareScoping {
                    scopes("public")
                    // No scopedSchema(...) calls — the universe set is the only thing to validate.
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
            .withArguments("assembleViaductCentralSchema", "--info", "--stacktrace")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":assembleViaductCentralSchema")?.outcome)
        // If the empty-`scopedSchemas` config caused slice 5 to skip materialization entirely,
        // this line would be absent. Its presence proves the always-added universe set ran.
        val matLines = result.output.lines().filter { it.contains("Validated scoped schema") }
        assertEquals(1, matLines.size, "expected exactly 1 materialization line; got:\n$matLines")
        matLines.single() shouldContain "[public]"
    }

    // Scenario 8 — unscoped app (no declareScoping at all) must skip materialization entirely.
    // Materialization for an unscoped app would be both wasteful (no scope directives to filter
    // by) and misleading (nothing scoped to validate). This is the counterpart to scenario 7.
    @Test
    fun `unscoped app skips materialization entirely`() {
        writeSettings()
        File(projectDir, "build.gradle.kts").writeText(buildScript(""))
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
            .withArguments("assembleViaductCentralSchema", "--info", "--stacktrace")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":assembleViaductCentralSchema")?.outcome)
        // No materialization work happened — neither the success nor the failure log line the
        // scoped path emits must be present.
        result.output shouldNotContain "Validated scoped schema"
        result.output shouldNotContain "Scoped schema for scope set"
    }

    // Scenario 9 — per-scope-set INFO logging visibility + dedup. Operators inspecting a slow
    // build via `--info` need to see which scope sets were materialized and roughly how expensive
    // each was. Without this line, a 30-second build spent inside materialization looks like a
    // mystery hang inside `assembleViaductCentralSchema`. Also asserts dedup on the underlying
    // scope set (INTERNAL_API's set == universe's set, one line, not two).
    @Test
    fun `per-scope-set INFO timing lines appear under --info and dedup on scope set`() {
        writeSettings()
        File(projectDir, "build.gradle.kts").writeText(
            buildScript(
                """
                declareScoping {
                    scopes("public", "internal")
                    scopedSchema("PUBLIC_API", "public")
                    scopedSchema("INTERNAL_API", "internal", "public")
                }
                """.trimIndent()
            )
        )
        // Query is in the "public" scope only. Materialized sets: {public} (from PUBLIC_API) and
        // {internal, public} (from INTERNAL_API AND from the universe — dedup). Under {public},
        // Query is kept and introspection passes. Under {internal, public} — the universe set —
        // A.9 fires; Query has `@scope`, so it passes.
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
            .withArguments("assembleViaductCentralSchema", "--info", "--stacktrace")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":assembleViaductCentralSchema")?.outcome)
        val matLines = result.output.lines().filter { it.contains("Validated scoped schema") }
        // 2 declared scoped schemas + 1 universe entry, with INTERNAL_API's set deduped against
        // the universe = 2 distinct materialization lines.
        assertEquals(2, matLines.size, "expected 2 dedup'd materialization lines; got:\n$matLines")
        val combined = matLines.joinToString("\n")
        combined shouldContain "[public]"
        combined shouldContain "[internal, public]"
        combined shouldContain "ms"
    }
}
