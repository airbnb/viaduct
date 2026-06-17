package viaduct.gradle.featureappcontract

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import java.io.File
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

/**
 * Layer 1: unit tests for [extractSchemaFromClassFile].
 * Layer 2: task-level tests that exercise the real [ContractSchemaExtractTask]
 *          via Gradle's [ProjectBuilder].
 *
 * Test fixtures are synthesized at test time using ASM [ClassWriter] so there is no
 * dependency on the real `@TestSchema` annotation class or a Kotlin compiler.
 */
class ContractSchemaExtractTest {
    // ── Layer 1: extractSchemaFromClassFile ──────────────────────────────────

    @Test
    fun `extracts schema from annotated class`() {
        val classFile = synthesizeClassFile(
            className = "com/example/MyContractTest",
            schemaValue = "type Query { hello: String }"
        )
        val result = extractSchemaFromClassFile(classFile)
        assertEquals("type Query { hello: String }", result)
    }

    @Test
    fun `returns null for class without annotation`() {
        val classFile = synthesizeClassFile(
            className = "com/example/PlainClass",
            schemaValue = null
        )
        assertNull(extractSchemaFromClassFile(classFile))
    }

    @Test
    fun `extracts multiline schema`() {
        val schema = """
            type Query {
              hello: String
              world: Int
            }
            type Foo {
              bar: String @resolver
            }
        """.trimIndent()
        val classFile = synthesizeClassFile(
            className = "com/example/MultilineTest",
            schemaValue = schema
        )
        assertEquals(schema, extractSchemaFromClassFile(classFile))
    }

    @Test
    fun `ignores unrelated annotations`() {
        val classFile = synthesizeClassFileWithUnrelatedAnnotation(
            className = "com/example/DeprecatedClass"
        )
        assertNull(extractSchemaFromClassFile(classFile))
    }

    @Test
    fun `ignores runtime-invisible annotation with matching descriptor`() {
        val classFile = synthesizeClassFile(
            className = "com/example/InvisibleTest",
            schemaValue = "type Query { x: String }",
            runtimeVisible = false
        )
        assertNull(extractSchemaFromClassFile(classFile))
    }

    // ── Layer 2: real task via ProjectBuilder ────────────────────────────────

    @Test
    fun `task extracts schemas keyed by package path`(
        @TempDir tempDir: File
    ) {
        val classesDir = tempDir.resolve("classes")
        writeClassFile(classesDir, "com/example/alpha/AlphaTest", "type Query { a: String }")
        writeClassFile(classesDir, "com/example/beta/BetaTest", "type Query { b: String }")

        val outputDir = tempDir.resolve("output")
        executeTask(classesDir, outputDir)

        assertEquals("type Query { a: String }", outputDir.resolve("com/example/alpha/schema.graphql").readText())
        assertEquals("type Query { b: String }", outputDir.resolve("com/example/beta/schema.graphql").readText())
    }

    @Test
    fun `task skips unannotated classes`(
        @TempDir tempDir: File
    ) {
        val classesDir = tempDir.resolve("classes")
        writeClassFile(classesDir, "com/example/alpha/AlphaTest", "type Query { a: String }")
        writeClassFile(classesDir, "com/example/alpha/Helper", null)

        val outputDir = tempDir.resolve("output")
        executeTask(classesDir, outputDir)

        assertTrue(outputDir.resolve("com/example/alpha/schema.graphql").exists())
        outputDir.walkTopDown().filter { it.name == "schema.graphql" }.toList().shouldHaveSize(1)
    }

    @Test
    fun `task rejects two contracts in same package`(
        @TempDir tempDir: File
    ) {
        val classesDir = tempDir.resolve("classes")
        writeClassFile(classesDir, "com/example/alpha/ContractA", "type Query { a: String }")
        writeClassFile(classesDir, "com/example/alpha/ContractB", "type Query { b: String }")

        val outputDir = tempDir.resolve("output")
        val e = assertThrows<IllegalStateException> { executeTask(classesDir, outputDir) }
        e.message!! shouldContain "Two contracts in package"
    }

    @Test
    fun `task skips inner and companion classes`(
        @TempDir tempDir: File
    ) {
        val classesDir = tempDir.resolve("classes")
        writeClassFile(classesDir, "com/example/alpha/AlphaTest", "type Query { a: String }")
        writeClassFile(classesDir, "com/example/alpha/AlphaTest\$Companion", null)

        val outputDir = tempDir.resolve("output")
        executeTask(classesDir, outputDir)

        assertTrue(outputDir.resolve("com/example/alpha/schema.graphql").exists())
        outputDir.walkTopDown().filter { it.name == "schema.graphql" }.toList().shouldHaveSize(1)
    }

    @Test
    fun `task trims indentation from extracted schema`(
        @TempDir tempDir: File
    ) {
        val rawSchema = """
            |    type Query {
            |      hello: String
            |    }
        """.trimMargin()
        val classesDir = tempDir.resolve("classes")
        writeClassFile(classesDir, "com/example/alpha/IndentTest", rawSchema)

        val outputDir = tempDir.resolve("output")
        executeTask(classesDir, outputDir)

        val content = outputDir.resolve("com/example/alpha/schema.graphql").readText()
        assertEquals(rawSchema.trimIndent().trim(), content)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Creates a real [ContractSchemaExtractTask] via [ProjectBuilder] and executes
     * its task action, exercising the actual Gradle task rather than a reimplementation.
     */
    private fun executeTask(
        classesDir: File,
        outputDir: File
    ) {
        val project = ProjectBuilder.builder().build()
        val task = project.tasks.create(
            "extractContractSchemas",
            ContractSchemaExtractTask::class.java
        )
        task.classesDirs.from(classesDir)
        task.outputDir.set(outputDir)
        task.extract()
    }

    private fun synthesizeClassFile(
        className: String,
        schemaValue: String?,
        runtimeVisible: Boolean = true
    ): File {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null)
        if (schemaValue != null) {
            val av = cw.visitAnnotation(TEST_SCHEMA_DESCRIPTOR, runtimeVisible)
            av.visit("value", schemaValue)
            av.visitEnd()
        }
        cw.visitEnd()

        val file = File.createTempFile("test-", ".class")
        file.deleteOnExit()
        file.writeBytes(cw.toByteArray())
        return file
    }

    private fun synthesizeClassFileWithUnrelatedAnnotation(className: String): File {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null)
        val av = cw.visitAnnotation("Ljava/lang/Deprecated;", true)
        av.visitEnd()
        cw.visitEnd()

        val file = File.createTempFile("test-", ".class")
        file.deleteOnExit()
        file.writeBytes(cw.toByteArray())
        return file
    }

    private fun writeClassFile(
        classesDir: File,
        internalName: String,
        schemaValue: String?
    ) {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        if (schemaValue != null) {
            val av = cw.visitAnnotation(TEST_SCHEMA_DESCRIPTOR, true)
            av.visit("value", schemaValue)
            av.visitEnd()
        }
        cw.visitEnd()

        val classFile = classesDir.resolve("$internalName.class")
        classFile.parentFile.mkdirs()
        classFile.writeBytes(cw.toByteArray())
    }
}
