package viaduct.gradle

import centralSchemaDirectoryName
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import viaduct.apiannotations.ExperimentalApi
import viaduct.scoping.ScopingErrorCodes

/**
 * Covers schema scope definitions end-to-end through a real Gradle build: that `scopes.yaml` reaches
 * `assembleViaductCentralSchema`, that its diagnostics reach the user, and that adding, editing and
 * deleting the file are all tracked as task input changes under the configuration cache.
 *
 * Per-rule coverage lives in `SchemaScopeDefinitionsTest` and `ViaductScopesYamlTest`; this is about the
 * wiring. TestKit rather than a gradletestapp because these cases mutate the file between builds, which
 * needs a project directory the test owns.
 */
@OptIn(ExperimentalApi::class)
class ViaductApplicationScopeValidationTest {
    @TempDir
    lateinit var projectDir: File

    @BeforeEach
    fun setUp() {
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
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                id("com.airbnb.viaduct.application-gradle-plugin")
            }
            """.trimIndent()
        )
        writeSchema(scoped = true)
    }

    /**
     * The application's own root-type extensions, in the directory the application plugin reads for
     * them. Scope-consistency validation requires every extension adding a non-tenant-local field to
     * declare `@scope`, so the scoped and unscoped variants differ.
     */
    private fun writeSchema(
        scoped: Boolean,
        scopeName: String = "public",
    ) {
        val scope = if (scoped) """ @scope(to: ["$scopeName"])""" else ""
        File(projectDir, "src/viaduct/schema").mkdirs()
        File(projectDir, "src/viaduct/schema/schema.graphqls").writeText(
            """
            extend type Query$scope {
              greeting: String
            }
            """.trimIndent()
        )
    }

    private fun writeScopes(content: String) {
        val file = File(projectDir, ViaductScopesYaml.RELATIVE_PATH)
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    private fun deleteScopes() {
        File(projectDir, ViaductScopesYaml.RELATIVE_PATH).delete()
    }

    private fun runner(vararg additionalArguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(
                System.getProperty("java.class.path").split(File.pathSeparator).map { File(it) }
            )
            .withArguments(
                listOf(
                    "assembleViaductCentralSchema",
                    "--configuration-cache",
                    "--configuration-cache-problems=fail",
                ) + additionalArguments
            )

    private fun build(): BuildResult = runner().build()

    private fun buildAndFail(): BuildResult = runner().buildAndFail()

    private val validScopes =
        """
        version: 1

        schemaScopes:
          - public

        scopedSchemas:
          - id: PUBLIC_API
            scopes: [public]
        """.trimIndent()

    @Test
    fun `a valid scopes file assembles the schema under the configuration cache`() {
        writeScopes(validScopes)

        val result = build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":assembleViaductCentralSchema")?.outcome)
    }

    @Test
    fun `a malformed scopes file fails the assembly task with its code and path`() {
        writeScopes("schemaScopes: [public\n")

        val result = buildAndFail()

        result.output shouldContain ScopingErrorCodes.SCOPES_FILE_MALFORMED
        result.output shouldContain ViaductScopesYaml.RELATIVE_PATH
    }

    @Test
    fun `an undeclared scope reference fails with every offending schema named`() {
        writeScopes(
            """
            version: 1
            schemaScopes:
              - public
            scopedSchemas:
              - id: Alpha
                scopes: [missing_a]
              - id: Beta
                scopes: [missing_b]
            """.trimIndent()
        )

        val result = buildAndFail()

        result.output shouldContain "invalid schema scope definitions"
        result.output shouldContain ScopingErrorCodes.SCOPED_SCHEMA_UNKNOWN_SCOPE
        result.output shouldContain "'Alpha'"
        result.output shouldContain "'Beta'"
        result.output shouldContain "missing_a"
        result.output shouldContain "missing_b"
    }

    @Test
    fun `an invalid scopes file fails before any schema output is written`() {
        writeScopes(
            """
            version: 1
            schemaScopes:
              - public
            scopedSchemas:
              - id: Alpha
                scopes: [missing]
            """.trimIndent()
        )

        val result = buildAndFail()

        result.output shouldContain ScopingErrorCodes.SCOPED_SCHEMA_UNKNOWN_SCOPE
        // Scoped to the task's own output: ValidateSchemaExtensionsTask writes a file of the same
        // name into its temporaryDir.
        assertEquals(
            emptyList<File>(),
            File(projectDir, "build/$centralSchemaDirectoryName").walkTopDown()
                .filter { it.invariantSeparatorsPath.endsWith("/" + ViaductApplicationPlugin.BUILTIN_SCHEMA_FILE) }
                .toList(),
        )
    }

    @Test
    fun `more than one scopes file fails rather than silently disabling scoping`() {
        writeScopes(validScopes)
        File(projectDir, "extra/scopes.yaml").apply {
            parentFile.mkdirs()
            writeText(validScopes)
        }
        File(projectDir, "build.gradle.kts").appendText(
            """

            tasks.named<viaduct.gradle.task.AssembleCentralSchemaTask>("assembleViaductCentralSchema") {
                scopesFile.from("extra/scopes.yaml")
            }
            """.trimIndent()
        )

        val result = buildAndFail()

        result.output shouldContain "Expected at most one scopes.yaml"
    }

    @Test
    fun `declaring scopes turns on scope-consistency validation of the schema`() {
        // Same schema as the no-scopes-file case; the only difference is that scopes.yaml exists.
        writeSchema(scoped = false)
        writeScopes(validScopes)

        val result = buildAndFail()

        result.output shouldContain "OBJECT_OR_INTERFACE_EXTENSION_SCOPE_DIRECTIVE_MISSING"
    }

    @Test
    fun `a scope used in the schema but absent from the scopes file fails the build`() {
        writeSchema(scoped = true, scopeName = "publik")
        writeScopes(validScopes)

        val result = buildAndFail()

        result.output shouldContain "scoped-schema validation failure(s)"
        result.output shouldContain "'publik' is not a valid scope name"
    }

    @Test
    fun `a declared scope set that cannot be built fails naming the scoped schema`() {
        writeScopes(
            """
            version: 1
            schemaScopes:
              - public
              - private
            scopedSchemas:
              - id: PRIVATE_API
                scopes: [private]
            """.trimIndent()
        )

        val result = buildAndFail()

        result.output shouldContain "scoped-schema validation failure(s)"
        result.output shouldContain "Could not build scoped schema 'PRIVATE_API'"
    }

    @Test
    fun `a project with no scopes file leaves scope-consistency validation off`() {
        writeSchema(scoped = false)

        val result = build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":assembleViaductCentralSchema")?.outcome)
    }

    @Test
    fun `adding, editing and deleting the scopes file each re-run the task and reuse the configuration cache`() {
        writeSchema(scoped = false)
        assertEquals(TaskOutcome.SUCCESS, build().task(":assembleViaductCentralSchema")?.outcome)

        writeSchema(scoped = true)
        writeScopes(validScopes)
        val added = build()
        assertEquals(TaskOutcome.SUCCESS, added.task(":assembleViaductCentralSchema")?.outcome)
        added.output shouldContain "Reusing configuration cache"

        writeScopes(validScopes.replace("PUBLIC_API", "RENAMED_API"))
        val edited = build()
        assertEquals(TaskOutcome.SUCCESS, edited.task(":assembleViaductCentralSchema")?.outcome)
        edited.output shouldContain "Reusing configuration cache"

        // Deleting the file drops back to unscoped, so the schema no longer needs its @scope.
        deleteScopes()
        writeSchema(scoped = false)
        val deleted = build()
        assertEquals(TaskOutcome.SUCCESS, deleted.task(":assembleViaductCentralSchema")?.outcome)
        deleted.output shouldContain "Reusing configuration cache"
    }

    @Test
    fun `editing the scopes file alone re-runs the task rather than reporting it up to date`() {
        writeScopes(validScopes)
        build()

        writeScopes(validScopes.replace("PUBLIC_API", "RENAMED_API"))

        assertEquals(TaskOutcome.SUCCESS, build().task(":assembleViaductCentralSchema")?.outcome)
    }

    @Test
    fun `plugin resources and generated inputs survive isolated configuration and build cache reuse`() {
        writeSchema(scoped = false)
        val directoryText = "\n\ndirective @directoryFixture on OBJECT\n"
        File(projectDir, "directory.graphqls").writeText(directoryText)
        val jarText = "\nscalar FixtureJson\ndirective @jarFixture(value: FixtureJson) on OBJECT\n"
        ZipOutputStream(File(projectDir, "fixture.jar").outputStream()).use { output ->
            output.putNextEntry(ZipEntry("nested/directives.graphqls"))
            output.write(jarText.toByteArray(Charsets.UTF_8))
            output.closeEntry()
        }
        val generatedInput = File(projectDir, "generated-input").apply { mkdirs() }
        generatedInput.resolve("keep.graphqls").writeText("directive @keep on OBJECT")
        val removed = generatedInput.resolve("removed.graphqls").apply { writeText("directive @removed on OBJECT") }
        File(projectDir, "build.gradle.kts").appendText(
            "\n" + """

            val generated = tasks.register<Sync>("generateFixtureSchema") {
                from(layout.projectDirectory.dir("generated-input"))
                into(layout.buildDirectory.dir("fixture-schema"))
            }
            extensions.getByType<viaduct.gradle.ViaductApplicationConventions>().pluginSchema.apply {
                into("dev.example.fixture/source/directory") {
                    from(resources.text.fromUri(layout.projectDirectory.file("directory.graphqls").asFile.toURI())) {
                        rename { "directives.graphqls" }
                    }
                }
                into("dev.example.fixture/source/nested") {
                    from(resources.text.fromUri("jar:" + layout.projectDirectory.file("fixture.jar").asFile.toURI() + "!/nested/directives.graphqls")) {
                        rename { "directives.graphqls" }
                    }
                }
                into("dev.example.fixture/generated/dev.example.generator") {
                    from(generated.map { it.destinationDir })
                }
            }
            """.trimIndent(),
        )

        fun run() = runner("--build-cache", "-Dorg.gradle.unsafe.isolated-projects=true").build()
        val first = run()
        assertEquals(TaskOutcome.SUCCESS, first.task(":generateFixtureSchema")?.outcome)
        val schema = File(projectDir, "build/$centralSchemaDirectoryName")
        assertEquals(directoryText, schema.resolve("schema/plugin/dev.example.fixture/source/directory/directives.graphqls").readText())
        assertEquals(jarText, schema.resolve("schema/plugin/dev.example.fixture/source/nested/directives.graphqls").readText())
        val generatedPath = "schema/plugin/dev.example.fixture/generated/dev.example.generator/removed.graphqls"
        assertEquals(removed.readText(), schema.resolve(generatedPath).readText())

        val repeated = run()
        repeated.output shouldContain "Reusing configuration cache"
        assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":assembleViaductCentralSchema")?.outcome)
        removed.delete()
        val changed = run()
        changed.output shouldContain "Reusing configuration cache"
        assertEquals(TaskOutcome.SUCCESS, changed.task(":assembleViaductCentralSchema")?.outcome)
        assertEquals(false, schema.resolve(generatedPath).exists())
        schema.deleteRecursively()
        val restored = run()
        assertEquals(TaskOutcome.FROM_CACHE, restored.task(":assembleViaductCentralSchema")?.outcome)
        assertEquals(jarText, schema.resolve("schema/plugin/dev.example.fixture/source/nested/directives.graphqls").readText())
        assertEquals(false, schema.resolve(generatedPath).exists())
    }
}
