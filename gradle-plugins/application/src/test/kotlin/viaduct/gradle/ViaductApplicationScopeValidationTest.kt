package viaduct.gradle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir

/**
 * TestKit coverage for the user-visible diagnostic surface of schema-scoping validation.
 *
 * Per-ID syntax fires at setter time (the precise stack trace is exercised by the in-project
 * ProjectBuilder tests); the cross-property invariants fire in the plugin's `afterEvaluate` hook,
 * which is only reachable through a real Gradle configuration pass — hence TestKit. The final case
 * is the config-cache hard gate: the `afterEvaluate` hook reading `schemaScoping.get()` must remain
 * cache-safe.
 */
class ViaductApplicationScopeValidationTest {
    @TempDir
    lateinit var projectDir: File

    private fun writeBuild(viaductApplicationBlock: String) {
        File(projectDir, "settings.gradle.kts").writeText("""rootProject.name = "scope-validation-test"""")
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                id("com.airbnb.viaduct.application-gradle-plugin")
            }
            viaductApplication {
                modulePackagePrefix.set("com.example.test")
            $viaductApplicationBlock
            }
            """.trimIndent()
        )
    }

    private fun runner() =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()

    @Test
    fun `a valid scope configuration configures cleanly`() {
        writeBuild(
            """
                declaredSchemaScopes(setOf("public", "internal"))
                declaredScopedSchemas(
                    "PUBLIC_API" to setOf("public"),
                    "INTERNAL_API" to setOf("public", "internal"),
                )
            """.trimIndent()
        )

        val result = runner().withArguments("help").build()
        assertContains(result.output, "BUILD SUCCESSFUL")
    }

    @Test
    fun `a single syntax error surfaces with file and line in the output`() {
        writeBuild(
            """
                declaredSchemaScopes(setOf("public", "BAD-ID"))
            """.trimIndent()
        )

        val result = runner().withArguments("help").buildAndFail()

        assertContains(result.output, "BUILD FAILED")
        assertContains(result.output, "SCOPE_ID_FORMAT_INVALID")
        assertContains(result.output, "BAD-ID")
        // The setter-time throw points the caller at the offending build script line.
        assertContains(result.output, "build.gradle.kts")
    }

    @Test
    fun `multiple cross-property errors appear together in one failure`() {
        writeBuild(
            """
                declaredSchemaScopes(setOf("public", "internal"))
                declaredScopedSchemas(
                    "PUBLIC_API" to setOf("public", "admin"),
                    "OPS_API" to setOf("ops"),
                )
            """.trimIndent()
        )

        val result = runner().withArguments("help").buildAndFail()

        assertContains(result.output, "BUILD FAILED")
        assertContains(result.output, "viaductApplication scope configuration is invalid")
        // Both subset violations are batched into the single aggregating failure.
        assertContains(result.output, "PUBLIC_API")
        assertContains(result.output, "OPS_API")
    }

    @Test
    fun `aggregated validation failure is configuration-cache safe`() {
        writeBuild(
            """
                declaredSchemaScopes(setOf("public"))
                declaredScopedSchemas("ADMIN_API" to setOf("admin"))
            """.trimIndent()
        )

        val result = runner()
            .withArguments("help", "--configuration-cache")
            .buildAndFail()

        // The failure must be the scope-validation failure itself, not a config-cache serialization
        // problem — i.e. the afterEvaluate hook captured only cache-safe state.
        assertContains(result.output, "viaductApplication scope configuration is invalid")
        assertContains(result.output, "SCOPE_SET_NOT_SUBSET")
    }
}
