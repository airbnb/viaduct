package viaduct.gradle

import java.io.File
import java.nio.file.Path
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import viaduct.gradle.task.AssembleFinalSchemaTask

class AssembleFinalSchemaTaskTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `final assembly preserves central bytes and removes additions from earlier runs`() {
        val task = task()
        val central = schemaFile("central/viaduct/central-schema/application/source/schema.graphqls", "type Query { value: String }\n")
        val plugin = schemaFile("plugin/viaduct/final-schema-contribution/plugin/dev.final/generated/dev.generator/final.graphqls", "type FinalOnly { value: String }\n")

        task.assemble()
        val output = task.outputDirectory.get().asFile
        assertEquals(central.readText(), output.resolve("viaduct/final-schema/application/source/schema.graphqls").readText())
        val finalPath = "viaduct/final-schema/plugin/dev.final/generated/dev.generator/final.graphqls"
        assertEquals(plugin.readText(), output.resolve(finalPath).readText())
        assertFalse(central.parentFile.resolve("final.graphqls").exists())

        plugin.delete()
        task.assemble()
        assertFalse(output.resolve(finalPath).exists())
        assertTrue(output.resolve("viaduct/final-schema/application/source/schema.graphqls").exists())
    }

    @Test
    fun `invalid final additions remove stale validated output and blame the final contributor`() {
        val task = task()
        schemaFile("central/viaduct/central-schema/application/source/schema.graphqls", "type Query { value: String }\n")
        val plugin = schemaFile("plugin/viaduct/final-schema-contribution/plugin/dev.final/generated/dev.generator/final.graphqls", "type FinalOnly { value: String }\n")
        task.assemble()
        plugin.writeText("extend type Query { broken: Undefined }\n")

        assertThrows<GradleException> { task.assemble() }
        assertFalse(task.outputDirectory.get().asFile.exists())
    }

    @Test
    fun `final plugin scalar return types remove stale validated output`() {
        val task = task()
        schemaFile("central/viaduct/central-schema/application/source/schema.graphqls", "type Query { value: String }\n")
        val plugin = schemaFile("plugin/viaduct/final-schema-contribution/plugin/dev.final/generated/dev.generator/final.graphqls", "scalar PluginScalar\n")
        task.assemble()
        assertTrue(task.outputDirectory.get().asFile.exists())
        plugin.appendText("extend type Query { forbidden: PluginScalar }\n")

        assertThrows<GradleException> { task.assemble() }
        assertFalse(task.outputDirectory.get().asFile.exists())
    }

    @Test
    fun `final contributors cannot claim the reserved framework identity`() {
        val task = task()
        schemaFile("central/viaduct/central-schema/application/source/schema.graphqls", "type Query { value: String }\n")
        listOf(
            "plugin/viaduct.core/source",
            "plugin/VIADUCT.CORE/generated/dev.generator",
            "plugin/dev.plugin/generated/VIADUCT.CORE",
        ).forEach { identity ->
            val input = schemaFile("plugin/viaduct/final-schema-contribution/$identity/final.graphqls", "scalar Hijack\n")
            val failure = assertThrows<GradleException> { task.assemble() }
            assertTrue(failure.message!!.contains("reserved final contributor"), failure.message)
            assertFalse(task.outputDirectory.get().asFile.exists())
            input.delete()
        }
    }

    @Test
    fun `final contributors cannot replace a central file even with identical bytes`() {
        val task = task()
        val path = "viaduct/schema/plugin/dev.final/generated/dev.generator/final.graphqls"
        schemaFile("central/${path.replace("viaduct/schema/", "viaduct/central-schema/")}", "type Query { value: String }\n")
        schemaFile("plugin/${path.replace("viaduct/schema/", "viaduct/final-schema-contribution/")}", "type Query { value: String }\n")

        val failure = assertThrows<GradleException> { task.assemble() }
        assertTrue(failure.message!!.contains("dev.generator"))
        assertFalse(task.outputDirectory.get().asFile.exists())
    }

    private fun task(): AssembleFinalSchemaTask {
        val project = ProjectBuilder.builder().withProjectDir(tempDir.toFile()).build()
        val task = project.tasks.register("assembleFinal", AssembleFinalSchemaTask::class.java).get()
        task.centralSchemaDirectory.set(tempDir.resolve("central").toFile().apply { mkdirs() })
        task.pluginSchemaFiles.from(tempDir.resolve("plugin").toFile().apply { mkdirs() })
        task.outputDirectory.set(tempDir.resolve("output").toFile())
        return task
    }

    private fun schemaFile(
        path: String,
        text: String
    ): File =
        tempDir.resolve(path).toFile().apply {
            parentFile.mkdirs()
            writeText(text)
        }
}
