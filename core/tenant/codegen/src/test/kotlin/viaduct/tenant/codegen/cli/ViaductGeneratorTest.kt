package viaduct.tenant.codegen.cli

import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import java.io.File
import kotlin.io.writeText
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.binary.extensions.toBinaryFile
import viaduct.graphql.schema.graphqljava.extensions.fromGraphQLSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.tenant.codegen.kotlingen.Args
import viaduct.tenant.codegen.kotlingen.generateFieldResolvers
import viaduct.tenant.codegen.kotlingen.generateNodeResolvers

class ViaductGeneratorTest {
    @TempDir
    private lateinit var tempDir: File
    private lateinit var schemaFile: File
    private lateinit var binarySchemaFile: File
    private lateinit var flagFile: File
    private lateinit var modernModuleGeneratedDir: File
    private lateinit var modernModuleOutputArchive: File
    private lateinit var metainfGeneratedDir: File
    private lateinit var metainfOutputArchive: File
    private lateinit var resolverGeneratedDir: File
    private lateinit var resolverOutputArchive: File
    private lateinit var tenantPackagePrefixFile: File

    @BeforeEach
    fun setup() {
        clearAllMocks()
        val sdl = "type Query { hello: String }"
        binarySchemaFile = File(tempDir, "schema.bgql").apply {
            createNewFile()
        }
        schemaFile = File(tempDir, "schema.gql").apply {
            createNewFile()
        }

        flagFile = File(tempDir, "viaduct_build_flags.bzl").apply {
            createNewFile()
        }
        schemaFile.writeText(sdl)
        flagFile.writeText(
            """
                viaduct_build_flags = {
                    "enable_binary_schema": "True",
                }
            """.trimIndent()
        )
        ViaductSchema.fromGraphQLSchema(listOf(schemaFile)).toBinaryFile(binarySchemaFile)
        modernModuleGeneratedDir = File(tempDir, "modern_module_generated").apply { mkdirs() }
        metainfGeneratedDir = File(tempDir, "metainf_generated").apply { mkdirs() }
        resolverGeneratedDir = File(tempDir, "resolver_generated").apply { mkdirs() }

        modernModuleOutputArchive = File(tempDir, "modern_module_output.zip")
        metainfOutputArchive = File(tempDir, "metainf_output.zip")
        resolverOutputArchive = File(tempDir, "resolver_output.zip")

        tenantPackagePrefixFile = File(tempDir, "tenant_package_prefix.txt").apply {
            createNewFile()
            writeText("com.test.prefix")
        }

        mockkStatic("viaduct.graphql.schema.graphqljava.extensions.FactoryKt")
        mockkStatic("viaduct.tenant.codegen.kotlingen.FieldResolverGeneratorKt")
        mockkStatic("viaduct.tenant.codegen.kotlingen.NodeResolverGeneratorKt")
        mockkStatic("viaduct.tenant.codegen.util.ZipUtil")

        val mockSchema = mockk<ViaductSchema>()

        every {
            ViaductSchema.fromTypeDefinitionRegistry(
                inputFiles = any<List<File>>(),
                timer = any()
            )
        } returns mockSchema

        every { mockSchema.generateFieldResolvers(any()) } just Runs
        every { mockSchema.generateNodeResolvers(any()) } just Runs
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `test successful execution with required options passes correct args to codegen`() {
        val fieldResolverArgsSlot = slot<Args>()
        val nodeResolverArgsSlot = slot<Args>()

        every { any<ViaductSchema>().generateFieldResolvers(capture(fieldResolverArgsSlot)) } just Runs
        every { any<ViaductSchema>().generateNodeResolvers(capture(nodeResolverArgsSlot)) } just Runs

        ViaductGenerator().main(
            listOf("--tenant_pkg", "test_tenant") +
                listOf("--modern_module_generated_directory", modernModuleGeneratedDir.absolutePath) +
                listOf("--metainf_generated_directory", metainfGeneratedDir.absolutePath) +
                listOf("--resolver_generated_directory", resolverGeneratedDir.absolutePath) +
                listOf("--schema_files", schemaFile.absolutePath) +
                listOf("--binary_schema_file", binarySchemaFile.absolutePath) +
                listOf("--flag_file", flagFile.absolutePath) +
                listOf("--tenant_package_prefix", "com.test")
        )

        verify { any<ViaductSchema>().generateFieldResolvers(any()) }
        verify { any<ViaductSchema>().generateNodeResolvers(any()) }

        with(fieldResolverArgsSlot.captured) {
            assertEquals("com.test.test.tenant", tenantPackage)
            assertEquals("com.test", tenantPackagePrefix)
            assertEquals("viaduct.api.grts", grtPackage)
            assertEquals(modernModuleGeneratedDir, modernModuleGeneratedDir)
            assertEquals(metainfGeneratedDir, metainfGeneratedDir)
            assertEquals(resolverGeneratedDir, resolverGeneratedDir)
            assertFalse(isFeatureAppTest)
        }

        with(nodeResolverArgsSlot.captured) {
            assertEquals("com.test.test.tenant", tenantPackage)
            assertEquals("com.test", tenantPackagePrefix)
        }
    }

    @Test
    fun `test with tenant package prefix from file`() {
        val argsSlot = slot<Args>()
        every { any<ViaductSchema>().generateFieldResolvers(capture(argsSlot)) } just Runs

        ViaductGenerator().main(
            listOf("--tenant_pkg", "test_tenant") +
                listOf("--modern_module_generated_directory", modernModuleGeneratedDir.absolutePath) +
                listOf("--metainf_generated_directory", metainfGeneratedDir.absolutePath) +
                listOf("--resolver_generated_directory", resolverGeneratedDir.absolutePath) +
                listOf("--schema_files", schemaFile.absolutePath) +
                listOf("--binary_schema_file", binarySchemaFile.absolutePath) +
                listOf("--flag_file", flagFile.absolutePath) +
                listOf("--tenant_package_prefix", "com.test") +
                listOf("--tenant_package_prefix_in_file", tenantPackagePrefixFile.absolutePath)
        )

        verify { any<ViaductSchema>().generateFieldResolvers(any()) }
        with(argsSlot.captured) {
            assertEquals("com.test.prefix", tenantPackage)
            assertEquals("com.test.prefix", tenantPackagePrefix)
            assertFalse(isFeatureAppTest)
        }
    }

    @Test
    fun `test with feature app test flag`() {
        val argsSlot = slot<Args>()
        every { any<ViaductSchema>().generateFieldResolvers(capture(argsSlot)) } just Runs

        ViaductGenerator().main(
            listOf("--tenant_pkg", "test_tenant") +
                listOf("--modern_module_generated_directory", modernModuleGeneratedDir.absolutePath) +
                listOf("--metainf_generated_directory", metainfGeneratedDir.absolutePath) +
                listOf("--resolver_generated_directory", resolverGeneratedDir.absolutePath) +
                listOf("--schema_files", schemaFile.absolutePath) +
                listOf("--binary_schema_file", binarySchemaFile.absolutePath) +
                listOf("--flag_file", flagFile.absolutePath) +
                listOf("--tenant_package_prefix", "com.test") +
                listOf("--isFeatureAppTest")
        )

        verify { any<ViaductSchema>().generateFieldResolvers(any()) }
        with(argsSlot.captured) {
            assertTrue(isFeatureAppTest)
            assertEquals("com.test.test.tenant", grtPackage)
        }
    }

    @Test
    fun `test with tenant from source name regex`() {
        ViaductGenerator().main(
            listOf("--tenant_pkg", "test_tenant") +
                listOf("--modern_module_generated_directory", modernModuleGeneratedDir.absolutePath) +
                listOf("--metainf_generated_directory", metainfGeneratedDir.absolutePath) +
                listOf("--resolver_generated_directory", resolverGeneratedDir.absolutePath) +
                listOf("--schema_files", schemaFile.absolutePath) +
                listOf("--binary_schema_file", binarySchemaFile.absolutePath) +
                listOf("--flag_file", flagFile.absolutePath) +
                listOf("--tenant_package_prefix", "com.test") +
                listOf("--tenant_from_source_name_regex", "test_regex")
        )

        verify { any<ViaductSchema>().generateFieldResolvers(any()) }
        verify { any<ViaductSchema>().generateNodeResolvers(any()) }
    }

    @Test
    fun `test partial archive specification throws error`() {
        assertThrows<IllegalArgumentException> {
            ViaductGenerator().main(
                listOf("--tenant_pkg", "test_tenant") +
                    listOf("--modern_module_generated_directory", modernModuleGeneratedDir.absolutePath) +
                    listOf("--modern_module_output_archive", modernModuleOutputArchive.absolutePath) +
                    listOf("--metainf_generated_directory", metainfGeneratedDir.absolutePath) +
                    listOf("--resolver_generated_directory", resolverGeneratedDir.absolutePath) +
                    listOf("--schema_files", schemaFile.absolutePath) +
                    listOf("--binary_schema_file", binarySchemaFile.absolutePath) +
                    listOf("--flag_file", flagFile.absolutePath) +
                    listOf("--tenant_package_prefix", "com.test")
            )
        }
    }

    @Test
    fun `test multiple schema files`() {
        // Disable binary schema to test the text schema path
        flagFile.writeText(
            """
                viaduct_build_flags = {
                    "enable_binary_schema": "False",
                }
            """.trimIndent()
        )
        val schemaFile2 = File(tempDir, "schema2.graphql").apply {
            createNewFile()
            writeText("type Mutation { update: String }")
        }
        // Use a flag file with binary schema disabled to test the fromTypeDefinitionRegistry path
        val noBinaryFlagFile = File(tempDir, "no_binary_flags.bzl").apply {
            createNewFile()
            writeText(
                """
                    viaduct_build_flags = {
                        "enable_binary_schema": "False",
                    }
                """.trimIndent()
            )
        }
        val filesSlot = slot<List<File>>()
        every {
            ViaductSchema.fromTypeDefinitionRegistry(
                inputFiles = capture(filesSlot),
                timer = any()
            )
        } returns mockk {
            every { generateFieldResolvers(any()) } just Runs
            every { generateNodeResolvers(any()) } just Runs
        }

        ViaductGenerator().main(
            listOf("--tenant_pkg", "test_tenant") +
                listOf("--modern_module_generated_directory", modernModuleGeneratedDir.absolutePath) +
                listOf("--metainf_generated_directory", metainfGeneratedDir.absolutePath) +
                listOf("--resolver_generated_directory", resolverGeneratedDir.absolutePath) +
                listOf("--schema_files", "${schemaFile.absolutePath},${schemaFile2.absolutePath}") +
                listOf("--binary_schema_file", binarySchemaFile.absolutePath) +
                listOf("--flag_file", noBinaryFlagFile.absolutePath) +
                listOf("--tenant_package_prefix", "com.test")
        )

        verify { ViaductSchema.fromTypeDefinitionRegistry(inputFiles = any<List<File>>(), timer = any()) }
        with(filesSlot.captured) {
            assertEquals(2, size)
            assertTrue(contains(schemaFile))
            assertTrue(contains(schemaFile2))
        }
    }

    @Test
    fun `test regex pattern unquoting`() {
        ViaductGenerator().main(
            listOf("--tenant_pkg", "test_tenant") +
                listOf("--modern_module_generated_directory", modernModuleGeneratedDir.absolutePath) +
                listOf("--metainf_generated_directory", metainfGeneratedDir.absolutePath) +
                listOf("--resolver_generated_directory", resolverGeneratedDir.absolutePath) +
                listOf("--schema_files", schemaFile.absolutePath) +
                listOf("--binary_schema_file", binarySchemaFile.absolutePath) +
                listOf("--flag_file", flagFile.absolutePath) +
                listOf("--tenant_package_prefix", "com.test") +
                listOf("--tenant_from_source_name_regex", "\"quoted_regex\"")
        )

        verify { any<ViaductSchema>().generateFieldResolvers(any()) }
        verify { any<ViaductSchema>().generateNodeResolvers(any()) }
    }
}
