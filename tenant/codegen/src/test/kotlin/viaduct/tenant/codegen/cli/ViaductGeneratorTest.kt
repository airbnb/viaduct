package viaduct.tenant.codegen.cli

import graphql.schema.idl.TypeDefinitionRegistry
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import java.io.File
import java.io.FileOutputStream
import kotlin.io.writeText
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import viaduct.graphql.schema.binary.writeBSchema
import viaduct.graphql.schema.graphqljava.GJSchemaRaw
import viaduct.graphql.schema.graphqljava.readTypesFromFiles
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
        writeBSchema(GJSchemaRaw.fromSDL(sdl), FileOutputStream(binarySchemaFile))
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

        mockkStatic("viaduct.graphql.schema.graphqljava.ReadFilesKt")
        mockkStatic("viaduct.tenant.codegen.kotlingen.FieldResolverGeneratorKt")
        mockkStatic("viaduct.tenant.codegen.kotlingen.NodeResolverGeneratorKt")
        mockkStatic("viaduct.tenant.codegen.util.ZipUtil")

        val mockTypeDefRegistry = mockk<TypeDefinitionRegistry>()
        val mockSchema = mockk<GJSchemaRaw>()

        every { readTypesFromFiles(any()) } returns mockTypeDefRegistry
        mockkObject(GJSchemaRaw.Companion)
        every {
            GJSchemaRaw.fromRegistry(
                registry = any(),
                timer = any(),
                valueConverter = any(),
                queryTypeName = any(),
                mutationTypeName = any(),
                subscriptionTypeName = any()
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

        every { any<GJSchemaRaw>().generateFieldResolvers(capture(fieldResolverArgsSlot)) } just Runs
        every { any<GJSchemaRaw>().generateNodeResolvers(capture(nodeResolverArgsSlot)) } just Runs

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

        verify { any<GJSchemaRaw>().generateFieldResolvers(any()) }
        verify { any<GJSchemaRaw>().generateNodeResolvers(any()) }

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
        every { any<GJSchemaRaw>().generateFieldResolvers(capture(argsSlot)) } just Runs

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

        verify { any<GJSchemaRaw>().generateFieldResolvers(any()) }
        with(argsSlot.captured) {
            assertEquals("com.test.prefix", tenantPackage)
            assertEquals("com.test.prefix", tenantPackagePrefix)
            assertFalse(isFeatureAppTest)
        }
    }

    @Test
    fun `test with feature app test flag`() {
        val argsSlot = slot<Args>()
        every { any<GJSchemaRaw>().generateFieldResolvers(capture(argsSlot)) } just Runs

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

        verify { any<GJSchemaRaw>().generateFieldResolvers(any()) }
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

        verify { any<GJSchemaRaw>().generateFieldResolvers(any()) }
        verify { any<GJSchemaRaw>().generateNodeResolvers(any()) }
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

        verify { any<GJSchemaRaw>().generateFieldResolvers(any()) }
        verify { any<GJSchemaRaw>().generateNodeResolvers(any()) }
    }
}
