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
 * End-to-end coverage of build-time schema materialization inside `AssembleCentralSchemaTask`.
 *
 * `ScopedSchemaValidator` is wired into the assembly path with two coverage lanes:
 * - **BASE** (`SchemaId.Base`) — validated for EVERY application, scoped or unscoped. BASE is the
 *   default runtime projection when a caller passes no explicit `schemaId` to `Viaduct.execute`.
 * - **Per-scope-set** — validated only for scoped applications, one materialization per distinct
 *   non-empty entry in `scopedSchemas.values`. An empty-alias entry (`scopedSchema("SOME_ALIAS")`)
 *   contributes nothing here because its runtime behavior collapses to BASE, which the BASE
 *   coverage above already handles.
 *
 * Failures across BASE and per-scope-set are aggregated into a single build failure with distinct
 * labels ("BASE schema" vs. "scope set [foo, bar]") so an operator can tell at a glance which
 * projection is broken.
 *
 * All SDL fixtures here use type-level `@scope` only. The framework's `@scope` directive is
 * defined on OBJECT | INPUT_OBJECT | ENUM | INTERFACE | UNION — NOT `FIELD_DEFINITION` — so per-
 * field `@scope` is a schema-syntax error and would never reach the materialization stage.
 * Keeping fixtures type-level-only guarantees the failure mode we assert here is the one we
 * actually shipped.
 */
class ViaductApplicationScopedSchemaMaterializationTest : ViaductApplicationTestKitFixture() {
    // Scenario 5 — the empty-set alias (`scopedSchema("FULL_ALIAS")` with no scope IDs) must NOT
    // silently short-circuit. An empty alias's runtime shape is BASE (`SchemaId.Base` is what a
    // caller gets when it passes no explicit schemaId), so BASE coverage is what validates it at
    // build time. Asserted here by pinning that the BASE INFO line appears when the ONLY declared
    // alias is the empty one, AND that no per-scope-set line fires (empty alias contributes
    // nothing to the loop).
    @Test
    fun `empty-set alias is covered by BASE validation, not by a per-scope-set loop entry`() {
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
        val baseLines = result.output.lines().filter { it.contains("Validated BASE schema") }
        assertTrue(baseLines.isNotEmpty()) {
            "expected an INFO line for BASE validation covering the empty-set alias; got:\n${result.output}"
        }
        // Empty alias contributes nothing to the per-scope-set loop.
        result.output shouldNotContain "Validated scoped schema"
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

    // Scenario 7 — a scoped app is allowed to register zero explicit scoped schemas (the
    // built-in schema still emits `@scope` when universe is declared but zero scoped schemas
    // are registered). In that config the per-scope-set loop iterates nothing — but BASE
    // materialization must still run so the runtime default projection has build-time coverage.
    // Without it, a scoped app that ships all-`@tenantLocal` Query fields would have NO
    // build-time check on the shape callers actually execute against.
    @Test
    fun `scoped app with zero declared scoped schemas still runs BASE validation`() {
        writeSettings()
        File(projectDir, "build.gradle.kts").writeText(
            buildScript(
                """
                declareScoping {
                    scopes("public")
                    // No scopedSchema(...) calls — the per-scope-set loop has nothing to iterate.
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
        val baseLines = result.output.lines().filter { it.contains("Validated BASE schema") }
        assertEquals(1, baseLines.size, "expected exactly 1 BASE validation line; got:\n$baseLines")
        // Per-scope-set loop iterated nothing.
        result.output shouldNotContain "Validated scoped schema"
    }

    // Scenario 8 — unscoped app (no declareScoping at all) skips the per-scope-set loop but must
    // still run BASE validation. BASE is the default runtime projection when a caller passes no
    // explicit `schemaId`, and that default applies uniformly to scoped and unscoped apps; without
    // BASE coverage here, an unscoped app whose entire Query is `@tenantLocal` would ship a
    // broken default with no build-time check.
    @Test
    fun `unscoped app runs BASE validation but skips per-scope-set materialization`() {
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
        // BASE validation must have run.
        val baseLines = result.output.lines().filter { it.contains("Validated BASE schema") }
        assertEquals(1, baseLines.size, "expected exactly 1 BASE validation line; got:\n$baseLines")
        // Per-scope-set loop must NOT have run (nothing scoped to iterate).
        result.output shouldNotContain "Validated scoped schema"
        result.output shouldNotContain "Scoped schema for scope set"
    }

    // Scenario 9 — per-scope-set INFO logging visibility + scope-set-based dedup. Operators
    // inspecting a slow build via `--info` need to see which scope sets were materialized and
    // roughly how expensive each was. Without this line, a 30-second build spent inside
    // materialization looks like a mystery hang inside `assembleViaductCentralSchema`. Also pins
    // that the dedup key is the scope set (not the alias name): two aliases that resolve to the
    // same scope set produce ONE materialization line, not two.
    @Test
    fun `per-scope-set INFO timing lines appear under --info and dedup on scope set`() {
        writeSettings()
        File(projectDir, "build.gradle.kts").writeText(
            buildScript(
                """
                declareScoping {
                    scopes("public", "internal")
                    scopedSchema("PUBLIC_API", "public")
                    scopedSchema("PUBLIC_API_V2", "public")
                    scopedSchema("INTERNAL_API", "internal", "public")
                }
                """.trimIndent()
            )
        )
        // Query is in the "public" scope only. Materialized distinct sets: {public} (from
        // PUBLIC_API and PUBLIC_API_V2, deduped) and {internal, public} (from INTERNAL_API).
        // Under {public} Query is kept and introspection passes; under {internal, public} Query
        // is also kept (its scope is a subset) and introspection passes.
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
        // 3 declared aliases, 2 distinct scope sets after dedup = 2 materialization lines.
        assertEquals(2, matLines.size, "expected 2 dedup'd materialization lines; got:\n$matLines")
        val combined = matLines.joinToString("\n")
        combined shouldContain "[public]"
        combined shouldContain "[internal, public]"
        combined shouldContain "ms"
        // BASE line also appears (validated for every app) — separate from the scoped lines.
        val baseLines = result.output.lines().filter { it.contains("Validated BASE schema") }
        assertEquals(1, baseLines.size, "expected exactly 1 BASE validation line; got:\n$baseLines")
    }
}
