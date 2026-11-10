package viaduct.graphql.schema

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SchemaProvenanceTest {
    @Test
    fun `path builders round trip source and generated ownership`() {
        assertEquals("${SchemaProvenance.pluginSourceDirectory("com.airbnb.viaduct")}/BUILTIN_SCHEMA.graphqls", SchemaProvenance.BUILTIN_SCHEMA_FILE)
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
        val path = "schema/plugin/dev.plugin/source/types.graphqls"
        listOf("ID", "String", "Int", "Float", "Boolean", "BackingData", "Date", "DateTime", "BigDecimal", "BigInteger", "Byte", "Short", "JSON", "Time", "Long").forEach { name ->
            assertFalse(SchemaProvenance.isPluginScalar(name, path), name)
        }
        assertTrue(SchemaProvenance.isPluginScalar("PluginValue", path))
        assertFalse(SchemaProvenance.isPluginScalar("PluginValue", "schema/module/catalog/source/types.graphqls"))
    }

    @Test
    fun `source ownership and generator identity are distinct`() {
        val source = SchemaProvenance.parse("schema/plugin/dev.example.plugin/source/nested/directives.graphqls")!!
        assertEquals("dev.example.plugin", source.supplyingPlugin)
        assertNull(source.tenantModule)
        assertEquals("nested/directives.graphqls", source.relativePath)
        val generated = SchemaProvenance.parse("schema/module/catalog.types/generated/dev.example.generator/types.graphqls")!!
        assertEquals("catalog.types", generated.owner)
        assertEquals("catalog/types", generated.tenantModule)
        assertEquals("dev.example.generator", generated.supplyingPlugin)
        assertNull(SchemaProvenance.parse("schema/application/source/common/query.graphqls")!!.supplyingPlugin)
    }

    @Test
    fun `malformed paths cannot claim plugin ownership`() {
        listOf(
            "schema/plugin/id/source",
            "schema/plugin/id/generated/file.graphqls",
            "schema/plugin/id/source/../file.graphqls",
            "/schema/plugin/id/source/file.graphqls",
            "schema/module//source/file.graphqls"
        ).forEach {
            assertNull(SchemaProvenance.parse(it), it)
        }
    }

    @Test
    fun `nested resource directories cannot change ownership`() {
        val source = SchemaProvenance.parse("schema/plugin/good/source/schema/plugin/other/source/types.graphqls")!!
        assertEquals("good", source.supplyingPlugin)
        assertEquals("schema/plugin/other/source/types.graphqls", source.relativePath)
        assertNull(SchemaProvenance.parse("schema/module/catalog/source/schema/plugin/other/source/types.graphqls")!!.supplyingPlugin)
    }
}
