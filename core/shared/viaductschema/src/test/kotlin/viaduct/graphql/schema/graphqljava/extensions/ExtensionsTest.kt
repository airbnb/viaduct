package viaduct.graphql.schema.graphqljava.extensions

import graphql.schema.GraphQLInputObjectField
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.SchemaWithData
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.gjSchemaFromFiles
import viaduct.graphql.schema.graphqljava.gjSchemaFromRegistry
import viaduct.graphql.schema.graphqljava.readTypes

internal class ExtensionsTest {
    private val sdl = """
        type T1 { f1: Int }
        type Query { foo: T1 }
    """.trimIndent()

    private fun mockFieldWithSourceLoc(pathStr: String): SchemaWithData.Field {
        val field = mockk<SchemaWithData.Field>()
        val sourceLoc = ViaductSchema.SourceLocation(pathStr)
        every { field.sourceLocation } returns sourceLoc
        return field
    }

    @Test
    fun testFieldWhenPresentation() {
        val field = mockFieldWithSourceLoc("repo/schema/presentation/someFile")
        assertTrue(field.isPresentation)
    }

    @Test
    fun testFieldWhenNotPresentation() {
        val field = mockFieldWithSourceLoc("repo/schema/data/someFile")
        assertFalse(field.isPresentation)
    }

    @Test
    fun testFieldWhenData() {
        val field = mockFieldWithSourceLoc("repo/schema/data/someFile")
        assertTrue(field.isData)
    }

    @Test
    fun testFieldWhenNotData() {
        val field = mockFieldWithSourceLoc("repo/schema/other/someFile")
        assertFalse(field.isData)
    }

    @Test
    fun testTenantExtractionWhenNoTenant() {
        val field = gjSchemaFromRegistry(readTypes(sdl))
        val tenant = field.types.values
            .first()
            .tenant
        assertEquals("NO_TENANT", tenant)
    }

    @Test
    fun testTypeDefWhenNoPresentation() {
        val schema = gjSchemaFromRegistry(readTypes(sdl))
        val typeDefs = schema.types.values
        assertTrue(typeDefs.none { it.isPresentation })
    }

    @Test
    fun testTypeDefWhenNoData() {
        val field = gjSchemaFromRegistry(readTypes(sdl))
        val typeDefs = field.types.values
        assertTrue(typeDefs.none { it.isData })
    }

    @Test
    fun testExtractingTenantFromTypeDefWhenTenantMatchesRegex() {
        val typeDefs = gjSchemaFromFiles(listOf(buildSchemaFileWithTenantMatchingStructure())).types.values
        assertTrue(typeDefs.any { it.tenant == "data/user/product" })
    }

    @Test
    fun testExtractingTenantFromFieldWhenNoTenantFound() {
        val field = spyk(mockFieldWithSourceLoc("repo/schema/other/someFile"))
        val gjDef = mockk<GraphQLInputObjectField>(relaxed = true)
        every { field.data } returns gjDef
        assertEquals("NO_TENANT", field.tenant)
    }

    @Test
    fun testInExtension() {
        val field = spyk(mockFieldWithSourceLoc("repo/schema/other/someFile"))
        val gjDef = mockk<GraphQLInputObjectField>(relaxed = true)
        every { field.data } returns gjDef
        every { field.containingDef } returns mockk(relaxed = true)
        assertFalse { field.inExtension }
    }

    @Test
    fun testHasExternalType() {
        val field = spyk(mockFieldWithSourceLoc("repo/schema/other/someFile"))

        val typeExpression = mockk<ViaductSchema.TypeExpr<SchemaWithData.TypeDef>>()
        every { typeExpression.baseTypeDef } returns gjSchemaFromFiles(
            listOf(
                buildSchemaFileWithTenantMatchingStructure()
            )
        ).types.values
            .first()
        every { field.type } returns typeExpression
        val gjDef = mockk<GraphQLInputObjectField>(relaxed = true)
        every { field.data } returns gjDef
        assertFalse { field.hasExternalType }
    }

    private fun buildSchemaFileWithTenantMatchingStructure(): File {
        // create tmp directory structure to match regex
        val topLevelDir = Files.createTempDirectory("test")
        val child = topLevelDir.resolve("data").resolve("user").resolve("product")
        Files.createDirectories(child)

        val classLoader = ExtensionsTest::class.java.classLoader
        val schemaStream = classLoader.getResourceAsStream("sample-schema/sample-schema.graphql")
        val temp: Path = Files.createTempFile(child, "sample-schema-", ".graphql")
        Files.copy(schemaStream, temp, StandardCopyOption.REPLACE_EXISTING)
        return temp.toFile()
    }
}
