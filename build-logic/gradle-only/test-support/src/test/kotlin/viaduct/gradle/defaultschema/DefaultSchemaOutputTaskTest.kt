package viaduct.gradle.defaultschema

import io.kotest.matchers.string.shouldContain
import java.io.File
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DefaultSchemaOutputTaskTest {
    @Test
    fun `extracted default schema declares parent directive`(
        @TempDir tempDir: File
    ) {
        val project = ProjectBuilder.builder().build()
        val task = project.tasks.register(
            "extractDefaultSchema",
            DefaultSchemaOutputTask::class.java
        ).get()
        val outputFile = tempDir.resolve("default_schema.graphqls")
        task.defaultSchemaFile.set(outputFile)

        task.extractDefaultSchema()

        outputFile.readText() shouldContain "directive @parent on FIELD_DEFINITION"
        outputFile.readText() shouldContain "scalar BigDecimal"
        outputFile.readText() shouldContain "scalar BigInteger"
    }
}
