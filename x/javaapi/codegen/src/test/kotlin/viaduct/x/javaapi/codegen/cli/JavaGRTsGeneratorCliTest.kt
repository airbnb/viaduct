package viaduct.x.javaapi.codegen.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Tests for the JavaGRTsGenerator CLI. */
class JavaGRTsGeneratorCliTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var originalOut: PrintStream
    private lateinit var outputCapture: ByteArrayOutputStream

    @BeforeEach
    fun setUp() {
        originalOut = System.out
        outputCapture = ByteArrayOutputStream()
        System.setOut(PrintStream(outputCapture))
    }

    @AfterEach
    fun tearDown() {
        System.setOut(originalOut)
    }

    @Test
    fun `generates types with verbose output`() {
        val schemaFile = tempDir.resolve("schema.graphqls")
        Files.writeString(
            schemaFile,
            """
            enum Status {
              ACTIVE
              INACTIVE
            }

            type User {
              id: ID!
              name: String!
            }
            """.trimIndent()
        )

        val grtOutputDir = tempDir.resolve("grt-output")
        val resolverOutputDir = tempDir.resolve("resolver-output")

        val cli = JavaGRTsGenerator()
        cli.parse(
            listOf(
                "--schema_files=${schemaFile.toAbsolutePath()}",
                "--grt_output_dir=${grtOutputDir.toAbsolutePath()}",
                "--grt_package=com.example",
                "--resolver_generated_dir=${resolverOutputDir.toAbsolutePath()}",
                "--tenant_package=com.example.tenant",
                "--verbose"
            )
        )

        val output = outputCapture.toString()
        assertThat(output).contains("Generated")
        assertThat(output).contains("enum(s)")
        assertThat(output).contains("object(s)")
        assertThat(output).contains("resolver(s)")

        // Verify files were created
        assertThat(grtOutputDir.resolve("com/example/Status.java")).exists()
        assertThat(grtOutputDir.resolve("com/example/User.java")).exists()
    }

    @Test
    fun `generates types without verbose output`() {
        val schemaFile = tempDir.resolve("schema.graphqls")
        Files.writeString(
            schemaFile,
            """
            enum Status {
              ACTIVE
            }
            """.trimIndent()
        )

        val grtOutputDir = tempDir.resolve("grt-output")
        val resolverOutputDir = tempDir.resolve("resolver-output")

        val cli = JavaGRTsGenerator()
        cli.parse(
            listOf(
                "--schema_files=${schemaFile.toAbsolutePath()}",
                "--grt_output_dir=${grtOutputDir.toAbsolutePath()}",
                "--grt_package=com.example",
                "--resolver_generated_dir=${resolverOutputDir.toAbsolutePath()}",
                "--tenant_package=com.example.tenant"
            )
        )

        val output = outputCapture.toString()
        assertThat(output).isEmpty()

        // Files should still be created
        assertThat(grtOutputDir.resolve("com/example/Status.java")).exists()
    }
}
