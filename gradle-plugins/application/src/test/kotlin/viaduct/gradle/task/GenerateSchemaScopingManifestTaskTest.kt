package viaduct.gradle.task

import java.io.File
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import viaduct.gradle.ViaductScopesYaml
import viaduct.scoping.SchemaScopingManifest

/**
 * Covers what the manifest task writes: the declared definitions at their runtime resource path, nothing
 * at all when the application declares no scopes, and nothing left behind when a declaration is removed.
 */
class GenerateSchemaScopingManifestTaskTest {
    @TempDir
    lateinit var projectDir: File

    private fun task(): GenerateSchemaScopingManifestTask {
        val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        return project.tasks.create("manifest", GenerateSchemaScopingManifestTask::class.java).apply {
            outputDirectory.set(project.layout.buildDirectory.dir("manifest-output"))
            scopesFile.setFrom(
                project.fileTree(ViaductScopesYaml.SOURCE_DIRECTORY) { include("scopes.yaml") },
            )
        }
    }

    private fun writeScopes(content: String) {
        projectDir.resolve(ViaductScopesYaml.RELATIVE_PATH).apply {
            parentFile.mkdirs()
            writeText(content)
        }
    }

    private fun manifestOf(task: GenerateSchemaScopingManifestTask): File = task.outputDirectory.get().asFile.resolve(SchemaScopingManifest.RESOURCE_PATH)

    @Test
    fun `an application that declares no scopes gets no manifest`() {
        val task = task()

        task.taskAction()

        assertFalse(manifestOf(task).exists(), "expected no manifest")
    }

    @Test
    fun `a version-only declaration gets no manifest`() {
        writeScopes("version: 1\n")
        val task = task()

        task.taskAction()

        assertFalse(manifestOf(task).exists(), "expected no manifest for an unscoped application")
    }

    @Test
    fun `declared definitions are written at the runtime resource path`() {
        writeScopes(
            """
            version: 1
            schemaScopes:
              - extras
              - default
            scopedSchemas:
              - id: publicSchemaWithExtras
                scopes: [extras, default]
              - id: publicSchema
                scopes: [default]
            """.trimIndent(),
        )
        val task = task()

        task.taskAction()

        assertEquals(
            """{"schemaScopes":["default","extras"],""" +
                """"scopedSchemas":[{"id":"publicSchema","scopes":["default"]},""" +
                """{"id":"publicSchemaWithExtras","scopes":["default","extras"]}],""" +
                """"version":"1"}""",
            manifestOf(task).readText(),
        )
    }

    @Test
    fun `removing the declaration removes the manifest rather than leaving a stale one`() {
        writeScopes("version: 1\nschemaScopes:\n  - default\n")
        val task = task()
        task.taskAction()
        assertTrue(manifestOf(task).exists(), "expected a manifest before the declaration is removed")

        projectDir.resolve(ViaductScopesYaml.RELATIVE_PATH).delete()
        task.scopesFile.setFrom(emptyList<File>())
        task.taskAction()

        assertFalse(manifestOf(task).exists(), "expected the stale manifest to be gone")
    }

    @Test
    fun `an invalid declaration fails the task instead of writing a manifest`() {
        writeScopes("version: 1\nschemaScopes:\n  - default\nscopedSchemas:\n  - id: A\n    scopes: [typo]\n")
        val task = task()

        val message = assertThrows<GradleException> { task.taskAction() }.message!!

        assertTrue(message.contains("SCOPED_SCHEMA_UNKNOWN_SCOPE"), message)
        assertFalse(manifestOf(task).exists(), "expected no manifest to be written")
    }
}
