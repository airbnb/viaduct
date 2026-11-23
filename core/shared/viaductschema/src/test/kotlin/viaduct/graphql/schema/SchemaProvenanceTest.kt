package viaduct.graphql.schema

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.utils.resources.ViaductResourceClaims
import viaduct.utils.resources.ViaductResourcePaths

class SchemaProvenanceTest {
    @Test
    fun `portable paths reject traversal reserved components and unsafe filenames`() {
        listOf("", ".", "..", "---", "CON", "nul.txt", "plugin.", "a/b", "a\\b", "C:", "x ", "x\n").forEach { id ->
            assertThrows<IllegalArgumentException>(id) { ViaductResourcePaths.requireId(id) }
        }
        listOf("../outside.graphqls", "/absolute.graphqls", "nested//file.graphqls", "types/CON.graphqls", "COM¹.graphqls", "file?.graphqls", "file\u0085.graphqls", "file. ").forEach { relative ->
            assertThrows<IllegalArgumentException>(relative) { ViaductResourcePaths.validate("viaduct/schema/module/catalog/source/$relative") }
        }
        assertNull(SchemaProvenance.parse("schema/plugin/dev.plugin/source/legacy.graphqls"))
    }

    @Test
    fun `portable collisions identify both contributors including case and unicode aliases`() {
        listOf("Types.graphqls" to "types.graphqls", "caf\u00e9.graphqls" to "cafe\u0301.graphqls").forEach { (first, second) ->
            val claims = ViaductResourceClaims()
            claims.claim("viaduct/schema/module/catalog/source/$first", "first producer")
            val failure = assertThrows<IllegalArgumentException> {
                claims.claim("viaduct/schema/module/catalog/source/$second", "second producer")
            }
            assertTrue(failure.message!!.contains("first producer"))
            assertTrue(failure.message!!.contains("second producer"))
        }
    }

    @Test
    fun `stage translation and archive mapping preserve identity and source names`() {
        val prepared = "viaduct/schema/module/catalog.pricing/generated/dev.generator/types/product.graphqls"
        val central = ViaductResourcePaths.atStage(prepared, "central-schema")
        val final = ViaductResourcePaths.atStage(central, "final-schema")
        assertEquals("viaduct/final-schema/module/catalog.pricing/generated/dev.generator/types/product.graphqls", final)
        assertEquals(SchemaProvenance.parse(prepared), SchemaProvenance.parse(final))
        assertEquals(final, ViaductResourcePaths.fromArchive(ViaductResourcePaths.archive(final)))
        assertThrows<IllegalArgumentException> { ViaductResourcePaths.archive(ViaductResourcePaths.archive(final)) }
        assertThrows<IllegalArgumentException> { ViaductResourcePaths.fromArchive(final) }
    }

    @Test
    fun `path builders round trip source and generated ownership`() {
        assertEquals("viaduct/central-schema/plugin/viaduct.core/generated/viaduct.core/BUILTIN_SCHEMA.graphqls", SchemaProvenance.BUILTIN_SCHEMA_FILE)
        val cases = listOf(
            SchemaProvenance.moduleSourceDirectory("catalog.pricing") to SchemaProvenance("module", "catalog.pricing", null, "nested/types.graphqls"),
            SchemaProvenance.moduleGeneratedDirectory("catalog.pricing", "dev.generator") to SchemaProvenance("module", "catalog.pricing", "dev.generator", "nested/types.graphqls"),
            SchemaProvenance.pluginSourceDirectory("dev.plugin") to SchemaProvenance("plugin", "dev.plugin", null, "nested/types.graphqls"),
            SchemaProvenance.pluginGeneratedDirectory("dev.plugin", "dev.generator") to SchemaProvenance("plugin", "dev.plugin", "dev.generator", "nested/types.graphqls"),
            SchemaProvenance.applicationSourceDirectory("schemabase") to SchemaProvenance("application", null, null, "schemabase/nested/types.graphqls"),
            SchemaProvenance.applicationSourceDirectory("common") to SchemaProvenance("application", null, null, "common/nested/types.graphqls"),
        )
        cases.forEach { (directory, expected) -> assertEquals(expected, SchemaProvenance.parse("$directory/nested/types.graphqls")) }
    }

    @Test
    fun `built in and framework scalars never become plugin scalars`() {
        val path = "viaduct/schema/plugin/dev.plugin/source/types.graphqls"
        listOf("ID", "String", "Int", "Float", "Boolean", "BackingData", "Date", "DateTime", "BigDecimal", "BigInteger", "Byte", "Short", "JSON", "Time", "Long").forEach { name ->
            assertFalse(SchemaProvenance.isPluginScalar(name, path), name)
        }
        assertTrue(SchemaProvenance.isPluginScalar("PluginValue", path))
        assertFalse(SchemaProvenance.isPluginScalar("PluginValue", "viaduct/schema/module/catalog/source/types.graphqls"))
    }

    @Test
    fun `source ownership and generator identity are distinct`() {
        val source = SchemaProvenance.parse("viaduct/schema/plugin/dev.example.plugin/source/nested/directives.graphqls")!!
        assertEquals("dev.example.plugin", source.supplyingPlugin)
        assertNull(source.tenantModule)
        assertEquals("nested/directives.graphqls", source.relativePath)
        val generated = SchemaProvenance.parse("viaduct/schema/module/catalog.types/generated/dev.example.generator/types.graphqls")!!
        assertEquals("catalog.types", generated.owner)
        assertEquals("catalog/types", generated.tenantModule)
        assertEquals("dev.example.generator", generated.supplyingPlugin)
        assertNull(SchemaProvenance.parse("viaduct/schema/application/source/common/query.graphqls")!!.supplyingPlugin)
    }

    @Test
    fun `malformed paths cannot claim plugin ownership`() {
        listOf(
            "viaduct/schema/plugin/id/source",
            "viaduct/schema/plugin/id/generated/file.graphqls",
            "viaduct/schema/plugin/id/source/../file.graphqls",
            "/viaduct/schema/plugin/id/source/file.graphqls",
            "viaduct/schema/module//source/file.graphqls"
        ).forEach {
            assertNull(SchemaProvenance.parse(it), it)
        }
    }

    @Test
    fun `nested resource directories cannot change ownership`() {
        val source = SchemaProvenance.parse("viaduct/schema/plugin/good/source/viaduct/schema/plugin/other/source/types.graphqls")!!
        assertEquals("good", source.supplyingPlugin)
        assertEquals("viaduct/schema/plugin/other/source/types.graphqls", source.relativePath)
        assertNull(SchemaProvenance.parse("viaduct/schema/module/catalog/source/viaduct/schema/plugin/other/source/types.graphqls")!!.supplyingPlugin)
    }
}
