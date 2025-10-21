package viaduct.gradle

import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import viaduct.gradle.task.AssembleCentralSchemaTask

/**
 * Tests for ViaductApplicationPlugin base schema functionality.
 *
 * These tests focus on the AssembleCentralSchemaTask's ability to discover and process
 * base schema files from src/main/viaduct/schemabase directory.
 */
class ViaductApplicationPluginTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private lateinit var task: AssembleCentralSchemaTask

    @BeforeEach
    fun setUp() {
        // Create a simple project for testing the task directly
        project = ProjectBuilder.builder()
            .withName("test")
            .withProjectDir(tempDir.toFile())
            .build()

        // Create the task directly instead of applying the full plugin
        task = project.tasks.create("testAssembleCentralSchema", AssembleCentralSchemaTask::class.java)
        task.outputDirectory.set(project.layout.buildDirectory.dir("test-output"))
    }

    @Test
    @Suppress("USELESS_IS_CHECK")
    fun `task should be created successfully`() {
        assertNotNull(task)
        assertTrue(task is AssembleCentralSchemaTask)
    }

    @Test
    fun `task should have proper output directory configured`() {
        assertNotNull(task.outputDirectory.get())
        assertTrue(task.outputDirectory.get().toString().contains("test-output"))
    }

    @Test
    fun `task should discover base schema files when directory exists`() {
        val baseSchemaDir = File(project.projectDir, "src/main/viaduct/schemabase")
        baseSchemaDir.mkdirs()

        val directivesFile = File(baseSchemaDir, "directives.graphqls")
        directivesFile.writeText(
            """
            # Custom application directives
            directive @customDirective(
              value: String!
            ) on FIELD_DEFINITION
            """.trimIndent()
        )

        val typesFile = File(baseSchemaDir, "types.graphqls")
        typesFile.writeText(
            """
            # Base types for the application
            interface BaseEntity {
              id: ID!
              createdAt: String
              updatedAt: String
            }
            """.trimIndent()
        )

        task.baseSchemaFiles.setFrom(
            project.fileTree(baseSchemaDir) {
                include("**/*.graphqls")
            }
        )

        val baseSchemaFiles = task.baseSchemaFiles.files
        assertEquals(2, baseSchemaFiles.size)

        val fileNames = baseSchemaFiles.map { it.name }.sorted()
        assertEquals(listOf("directives.graphqls", "types.graphqls"), fileNames)
    }

    @Test
    fun `task should handle missing base schema directory gracefully`() {
        val baseSchemaFiles = task.baseSchemaFiles.files
        assertTrue(baseSchemaFiles.isEmpty())
    }

    @Test
    fun `task should only include graphqls files from base schema directory`() {
        val baseSchemaDir = File(project.projectDir, "src/main/viaduct/schemabase")
        baseSchemaDir.mkdirs()

        File(baseSchemaDir, "valid.graphqls").writeText("type Test { id: ID! }")
        File(baseSchemaDir, "readme.txt").writeText("This is not a schema file")
        File(baseSchemaDir, "config.json").writeText("{}")

        task.baseSchemaFiles.setFrom(
            project.fileTree(baseSchemaDir) {
                include("**/*.graphqls")
            }
        )

        val baseSchemaFiles = task.baseSchemaFiles.files
        assertEquals(1, baseSchemaFiles.size)
        assertEquals("valid.graphqls", baseSchemaFiles.first().name)
    }

    @Test
    fun `task should discover schema files in nested directories`() {
        val baseSchemaDir = File(project.projectDir, "src/main/viaduct/schemabase")
        val subDir = File(baseSchemaDir, "common")
        subDir.mkdirs()

        File(baseSchemaDir, "root.graphqls").writeText("type Root { id: ID! }")
        File(subDir, "nested.graphqls").writeText("type Nested { name: String! }")

        task.baseSchemaFiles.setFrom(
            project.fileTree(baseSchemaDir) {
                include("**/*.graphqls")
            }
        )

        val baseSchemaFiles = task.baseSchemaFiles.files
        assertEquals(2, baseSchemaFiles.size)
        val fileNames = baseSchemaFiles.map { it.name }.sorted()
        assertEquals(listOf("nested.graphqls", "root.graphqls"), fileNames)
    }

    @Test
    fun `task should handle empty schema files and multiple file extensions correctly`() {
        val baseSchemaDir = File(project.projectDir, "src/main/viaduct/schemabase")
        baseSchemaDir.mkdirs()

        // Empty file
        File(baseSchemaDir, "empty.graphqls").writeText("")

        // File with only comments
        File(baseSchemaDir, "comments.graphqls").writeText(
            """
            # This is just a comment file
            # No actual schema definitions
            """.trimIndent()
        )

        // Valid schema file
        File(baseSchemaDir, "valid.graphqls").writeText("type ValidType { id: ID! }")

        // Files that should be ignored
        File(baseSchemaDir, "schema.graphql").writeText("type Wrong { id: ID! }") // wrong extension
        File(baseSchemaDir, "README.md").writeText("Documentation")

        task.baseSchemaFiles.setFrom(
            project.fileTree(baseSchemaDir) {
                include("**/*.graphqls")
            }
        )

        val baseSchemaFiles = task.baseSchemaFiles.files
        assertEquals(3, baseSchemaFiles.size) // empty.graphqls, comments.graphqls, valid.graphqls

        val fileNames = baseSchemaFiles.map { it.name }.sorted()
        assertEquals(listOf("comments.graphqls", "empty.graphqls", "valid.graphqls"), fileNames)

        val allFileNames = baseSchemaDir.listFiles()?.map { it.name } ?: emptyList()
        assertTrue(allFileNames.contains("schema.graphql")) // File exists but not in result
        assertTrue(allFileNames.contains("README.md")) // File exists but not in result
    }
}
