package viaduct.x.javaapi.codegen.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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

    @Test
    fun `reads grt package from file`() {
        val schemaFile = tempDir.resolve("schema.graphqls")
        Files.writeString(schemaFile, "enum Color { RED GREEN }")

        val packageFile = tempDir.resolve("grt_package.txt")
        Files.writeString(packageFile, "  com.fromfile  \n")

        val grtOutputDir = tempDir.resolve("grt-output")
        val resolverOutputDir = tempDir.resolve("resolver-output")

        JavaGRTsGenerator().parse(
            listOf(
                "--schema_files=${schemaFile.toAbsolutePath()}",
                "--grt_output_dir=${grtOutputDir.toAbsolutePath()}",
                "--grt_package_file=${packageFile.toAbsolutePath()}",
                "--resolver_generated_dir=${resolverOutputDir.toAbsolutePath()}",
                "--tenant_package=com.tenant"
            )
        )

        assertThat(grtOutputDir.resolve("com/fromfile/Color.java")).exists()
    }

    @Test
    fun `reads tenant package from file`() {
        val schemaFile = tempDir.resolve("schema.graphqls")
        Files.writeString(
            schemaFile,
            """
            enum Color { RED GREEN }
            type User { id: ID! }
            """.trimIndent()
        )

        val tenantPackageFile = tempDir.resolve("tenant_package.txt")
        Files.writeString(tenantPackageFile, "  com.tenantfromfile  \n")

        val grtOutputDir = tempDir.resolve("grt-output")
        val resolverOutputDir = tempDir.resolve("resolver-output")

        JavaGRTsGenerator().parse(
            listOf(
                "--schema_files=${schemaFile.toAbsolutePath()}",
                "--grt_output_dir=${grtOutputDir.toAbsolutePath()}",
                "--grt_package=com.example",
                "--resolver_generated_dir=${resolverOutputDir.toAbsolutePath()}",
                "--tenant_package_file=${tenantPackageFile.toAbsolutePath()}",
                "--verbose"
            )
        )

        // Verifies tenant_package_file was read and the codegen completed successfully
        assertThat(grtOutputDir.resolve("com/example/Color.java")).exists()
        val output = outputCapture.toString()
        assertThat(output).contains("Generated")
    }

    @Test
    fun `tenant package defaults to grt package when neither option provided`() {
        val schemaFile = tempDir.resolve("schema.graphqls")
        Files.writeString(
            schemaFile,
            """
            enum Priority { LOW HIGH }
            """.trimIndent()
        )

        val grtOutputDir = tempDir.resolve("grt-output")
        val resolverOutputDir = tempDir.resolve("resolver-output")

        // Omitting --tenant_package so it defaults to grt_package
        JavaGRTsGenerator().parse(
            listOf(
                "--schema_files=${schemaFile.toAbsolutePath()}",
                "--grt_output_dir=${grtOutputDir.toAbsolutePath()}",
                "--grt_package=com.fallback",
                "--resolver_generated_dir=${resolverOutputDir.toAbsolutePath()}"
            )
        )

        // Codegen completes without error; GRT output uses the specified package
        assertThat(grtOutputDir.resolve("com/fallback/Priority.java")).exists()
    }

    @Test
    fun `includeRootTypes removes Mutation and Subscription files`() {
        val schemaFile = tempDir.resolve("schema.graphqls")
        Files.writeString(
            schemaFile,
            """
            type Query {
              hello: String
            }
            type Mutation {
              _: String
            }
            type Subscription {
              _: String
            }
            type User {
              id: ID!
            }
            """.trimIndent()
        )

        val grtOutputDir = tempDir.resolve("grt-output")
        val resolverOutputDir = tempDir.resolve("resolver-output")

        JavaGRTsGenerator().parse(
            listOf(
                "--schema_files=${schemaFile.toAbsolutePath()}",
                "--grt_output_dir=${grtOutputDir.toAbsolutePath()}",
                "--grt_package=com.example",
                "--resolver_generated_dir=${resolverOutputDir.toAbsolutePath()}",
                "--include_root_types"
            )
        )

        // Query should be generated (root types included)
        assertThat(grtOutputDir.resolve("com/example/Query.java")).exists()
        // Mutation and Subscription should be removed
        assertThat(grtOutputDir.resolve("com/example/Mutation.java")).doesNotExist()
        assertThat(grtOutputDir.resolve("com/example/Subscription.java")).doesNotExist()
        // Regular types should still exist
        assertThat(grtOutputDir.resolve("com/example/User.java")).exists()
    }

    @Test
    fun `archives grt output into srcjar`() {
        val schemaFile = tempDir.resolve("schema.graphqls")
        Files.writeString(schemaFile, "enum Size { SMALL LARGE }")

        val grtOutputDir = tempDir.resolve("grt-output")
        val resolverOutputDir = tempDir.resolve("resolver-output")
        val grtArchive = tempDir.resolve("output.srcjar")

        JavaGRTsGenerator().parse(
            listOf(
                "--schema_files=${schemaFile.toAbsolutePath()}",
                "--grt_output_dir=${grtOutputDir.toAbsolutePath()}",
                "--grt_package=com.example",
                "--resolver_generated_dir=${resolverOutputDir.toAbsolutePath()}",
                "--tenant_package=com.example",
                "--grt_output_archive=${grtArchive.toAbsolutePath()}"
            )
        )

        // Archive should exist and contain the generated file
        assertThat(grtArchive).exists()
        ZipFile(grtArchive.toFile()).use { zip ->
            val entries = zip.entries().toList().map { it.name }
            assertThat(entries).anyMatch { it.endsWith("Size.java") }
        }
        // Original directory should be deleted after archiving
        assertThat(grtOutputDir).doesNotExist()
    }

    @Test
    fun `archives resolver output into srcjar`() {
        val schemaFile = tempDir.resolve("schema.graphqls")
        Files.writeString(schemaFile, "enum Status { ACTIVE }")

        val grtOutputDir = tempDir.resolve("grt-output")
        val resolverOutputDir = tempDir.resolve("resolver-output")
        val resolverArchive = tempDir.resolve("resolvers.srcjar")

        JavaGRTsGenerator().parse(
            listOf(
                "--schema_files=${schemaFile.toAbsolutePath()}",
                "--grt_output_dir=${grtOutputDir.toAbsolutePath()}",
                "--grt_package=com.example",
                "--resolver_generated_dir=${resolverOutputDir.toAbsolutePath()}",
                "--tenant_package=com.example.tenant",
                "--resolver_output_archive=${resolverArchive.toAbsolutePath()}"
            )
        )

        // Archive is created and original directory is cleaned up
        assertThat(resolverArchive).exists()
        assertThat(resolverOutputDir).doesNotExist()
    }

    @Test
    fun `errors when neither grt_package nor grt_package_file provided`() {
        val schemaFile = tempDir.resolve("schema.graphqls")
        Files.writeString(schemaFile, "enum Status { ACTIVE }")

        val grtOutputDir = tempDir.resolve("grt-output")
        val resolverOutputDir = tempDir.resolve("resolver-output")

        assertThatThrownBy {
            JavaGRTsGenerator().parse(
                listOf(
                    "--schema_files=${schemaFile.toAbsolutePath()}",
                    "--grt_output_dir=${grtOutputDir.toAbsolutePath()}",
                    "--resolver_generated_dir=${resolverOutputDir.toAbsolutePath()}"
                )
            )
        }.hasMessageContaining("--grt_package or --grt_package_file must be provided")
    }
}
