package viaduct.x.javaapi.codegen.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
        assertTrue(output.contains("Generated"))
        assertTrue(output.contains("enum(s)"))
        assertTrue(output.contains("object(s)"))
        assertTrue(output.contains("resolver(s)"))

        // Verify files were created
        assertTrue(Files.exists(grtOutputDir.resolve("com/example/Status.java")))
        assertTrue(Files.exists(grtOutputDir.resolve("com/example/User.java")))
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
        assertTrue(output.isEmpty())

        // Files should still be created
        assertTrue(Files.exists(grtOutputDir.resolve("com/example/Status.java")))
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

        assertTrue(Files.exists(grtOutputDir.resolve("com/fromfile/Color.java")))
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
        assertTrue(Files.exists(grtOutputDir.resolve("com/example/Color.java")))
        val output = outputCapture.toString()
        assertTrue(output.contains("Generated"))
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
        assertTrue(Files.exists(grtOutputDir.resolve("com/fallback/Priority.java")))
    }

    @Test
    fun `includeRootTypes keeps Mutation but removes Subscription file`() {
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

        // Query and Mutation should be generated (root types included, _ field is skipped)
        assertTrue(Files.exists(grtOutputDir.resolve("com/example/Query.java")))
        assertTrue(Files.exists(grtOutputDir.resolve("com/example/Mutation.java")))
        // Subscription should be removed (no viaduct.java.api.types.Subscription marker interface)
        assertFalse(Files.exists(grtOutputDir.resolve("com/example/Subscription.java")))
        // Regular types should still exist
        assertTrue(Files.exists(grtOutputDir.resolve("com/example/User.java")))
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
        assertTrue(Files.exists(grtArchive))
        ZipFile(grtArchive.toFile()).use { zip ->
            val entries = zip.entries().toList().map { it.name }
            assertTrue(entries.any { it.endsWith("Size.java") })
        }
        // Original directory should be deleted after archiving
        assertFalse(Files.exists(grtOutputDir))
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
        assertTrue(Files.exists(resolverArchive))
        assertFalse(Files.exists(resolverOutputDir))
    }

    @Test
    fun `resolver bases import from grt package when it differs from tenant package`() {
        val schemaFile = tempDir.resolve("schema.graphqls")
        Files.writeString(
            schemaFile,
            """
            directive @resolver on OBJECT | FIELD_DEFINITION
            type Query {
              greeting: String @resolver
            }
            """.trimIndent()
        )

        val grtOutputDir = tempDir.resolve("grt-output")
        val resolverOutputDir = tempDir.resolve("resolver-output")

        JavaGRTsGenerator().parse(
            listOf(
                "--schema_files=${schemaFile.toAbsolutePath()}",
                "--grt_output_dir=${grtOutputDir.toAbsolutePath()}",
                "--grt_package=viaduct.java.grts",
                "--resolver_generated_dir=${resolverOutputDir.toAbsolutePath()}",
                "--tenant_package=com.example.viadapp.resolvers",
                "--include_root_types"
            )
        )

        val resolverContent = java.nio.file.Files.readString(
            resolverOutputDir.resolve("com/example/viadapp/resolvers/resolverbases/QueryResolvers.java")
        )
        assertTrue(resolverContent.contains("package com.example.viadapp.resolvers.resolverbases;"))
        assertTrue(resolverContent.contains("import viaduct.java.grts.*;"))
        assertFalse(resolverContent.contains("import com.example.viadapp.resolvers.*;"))
    }

    @Test
    fun `errors when neither grt_package nor grt_package_file provided`() {
        val schemaFile = tempDir.resolve("schema.graphqls")
        Files.writeString(schemaFile, "enum Status { ACTIVE }")

        val grtOutputDir = tempDir.resolve("grt-output")
        val resolverOutputDir = tempDir.resolve("resolver-output")

        val ex = assertThrows<Exception> {
            JavaGRTsGenerator().parse(
                listOf(
                    "--schema_files=${schemaFile.toAbsolutePath()}",
                    "--grt_output_dir=${grtOutputDir.toAbsolutePath()}",
                    "--resolver_generated_dir=${resolverOutputDir.toAbsolutePath()}"
                )
            )
        }
        assertTrue(ex.message!!.contains("--grt_package or --grt_package_file must be provided"))
    }
}
