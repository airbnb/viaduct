package viaduct.gradle.featureappcontract

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Layer 3: Gradle TestKit functional tests for [ViaductContractSchemaPublisherPlugin].
 *
 * Validates that:
 * - `extractContractSchemas` runs after testFixtures compilation
 * - No task dependency cycle exists
 * - Extracted schema output is correct
 *
 * Two variants exercise both Java and Kotlin testFixtures sources. The annotation
 * must live in `viaduct.tenant.runtime.fixtures` so the compiled descriptor matches
 * what the extractor looks for.
 */
class ContractSchemaPublisherPluginTest {
    @Test
    fun `java testFixtures - extracts schema after compilation`(
        @TempDir projectDir: File
    ) {
        assertExtractsSchema(
            projectDir,
            fixture = JavaFixture,
            expectedCompileTask = ":compileTestFixturesJava"
        )
    }

    @Test
    fun `kotlin testFixtures - extracts schema after compilation`(
        @TempDir projectDir: File
    ) {
        assertExtractsSchema(
            projectDir,
            fixture = KotlinFixture,
            expectedCompileTask = ":compileTestFixturesKotlin"
        )
    }

    // ── Test fixture definitions ─────────────────────────────────────────────

    private interface TestFixture {
        val settingsExtra: String get() = ""
        val buildPlugins: String
        val buildExtra: String get() = ""

        fun writeAnnotation(projectDir: File)

        fun writeContract(projectDir: File)

        val contractPkgPath: String
        val expectedSchema: String
    }

    private object JavaFixture : TestFixture {
        override val buildPlugins = """
            java
            `java-test-fixtures`
            id("feature-app-contracts")
        """.trimIndent()

        override fun writeAnnotation(projectDir: File) {
            val dir = projectDir.resolve("src/testFixtures/java/viaduct/tenant/runtime/fixtures")
            dir.mkdirs()
            dir.resolve("TestSchema.java").writeText(
                """
                package viaduct.tenant.runtime.fixtures;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.TYPE)
                public @interface TestSchema {
                    String value();
                }
                """.trimIndent()
            )
        }

        override fun writeContract(projectDir: File) {
            val dir = projectDir.resolve("src/testFixtures/java/com/example/contracts/alpha")
            dir.mkdirs()
            dir.resolve("AlphaContractTest.java").writeText(
                """
                package com.example.contracts.alpha;

                import viaduct.tenant.runtime.fixtures.TestSchema;

                @TestSchema("type Query { hello: String }")
                public abstract class AlphaContractTest {}
                """.trimIndent()
            )
        }

        override val contractPkgPath = "com/example/contracts/alpha"
        override val expectedSchema = "type Query { hello: String }"
    }

    private object KotlinFixture : TestFixture {
        override val settingsExtra = """
            pluginManagement {
                repositories {
                    gradlePluginPortal()
                }
            }
        """.trimIndent()

        override val buildPlugins = """
            kotlin("jvm") version "1.9.24"
            `java-test-fixtures`
            id("feature-app-contracts")
        """.trimIndent()

        override val buildExtra = "repositories { mavenCentral() }"

        override fun writeAnnotation(projectDir: File) {
            val dir = projectDir.resolve("src/testFixtures/kotlin/viaduct/tenant/runtime/fixtures")
            dir.mkdirs()
            dir.resolve("TestSchema.kt").writeText(
                """
                package viaduct.tenant.runtime.fixtures

                @Retention(AnnotationRetention.RUNTIME)
                @Target(AnnotationTarget.CLASS)
                annotation class TestSchema(val value: String)
                """.trimIndent()
            )
        }

        override fun writeContract(projectDir: File) {
            val dir = projectDir.resolve("src/testFixtures/kotlin/com/example/contracts/beta")
            dir.mkdirs()
            dir.resolve("BetaContractTest.kt").writeText(
                """
                package com.example.contracts.beta

                import viaduct.tenant.runtime.fixtures.TestSchema

                @TestSchema("type Query { world: Int }")
                abstract class BetaContractTest
                """.trimIndent()
            )
        }

        override val contractPkgPath = "com/example/contracts/beta"
        override val expectedSchema = "type Query { world: Int }"
    }

    // ── Shared runner ────────────────────────────────────────────────────────

    private fun assertExtractsSchema(
        projectDir: File,
        fixture: TestFixture,
        expectedCompileTask: String
    ) {
        projectDir.resolve("settings.gradle.kts").writeText(
            buildString {
                if (fixture.settingsExtra.isNotEmpty()) {
                    appendLine(fixture.settingsExtra)
                }
                append("rootProject.name = \"test-project\"")
            }
        )

        projectDir.resolve("build.gradle.kts").writeText(
            buildString {
                appendLine("plugins {")
                fixture.buildPlugins.lines().forEach { appendLine("    $it") }
                appendLine("}")
                if (fixture.buildExtra.isNotEmpty()) {
                    appendLine(fixture.buildExtra)
                }
            }
        )

        fixture.writeAnnotation(projectDir)
        fixture.writeContract(projectDir)

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("extractContractSchemas", "--stacktrace")
            .withPluginClasspath()
            .build()

        assertThat(result.task(expectedCompileTask)?.outcome)
            .isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.task(":extractContractSchemas")?.outcome)
            .isEqualTo(TaskOutcome.SUCCESS)

        val schemaFile = projectDir.resolve(
            "build/extracted-contract-schemas/${fixture.contractPkgPath}/schema.graphql"
        )
        assertThat(schemaFile).exists()
        assertThat(schemaFile.readText()).isEqualTo(fixture.expectedSchema)
    }
}
