package viaduct.graphql.schema.cli

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.test.createSchemaWithSourceLocations

class TenantExtractorTest {
    // ========== extractTenant() tests ==========

    @Test
    fun `extractTenant returns module path before src`() {
        // Raw extractTenant returns full path before /src/ (no prefix stripping)
        assertEquals("modules/data/user", extractTenant("modules/data/user/src/resources/User.graphqls"))
    }

    @Test
    fun `extractTenant with prefix strips the prefix`() {
        assertEquals("data/user", extractTenant("modules/data/user/src/resources/User.graphqls", "modules/"))
    }

    @Test
    fun `findCommonPrefix finds longest common prefix`() {
        // findCommonPrefix works on directory paths (with trailing /)
        val paths = listOf(
            "/home/user/repo/modules/foo/",
            "/home/user/repo/modules/bar/",
            "/home/user/repo/modules/baz/"
        )
        assertEquals("/home/user/repo/modules/", findCommonPrefix(paths))
    }

    @Test
    fun `findCommonPrefix returns root for divergent paths`() {
        val paths = listOf(
            "/foo/bar/",
            "/baz/qux/"
        )
        assertEquals("/", findCommonPrefix(paths))
    }

    @Test
    fun `findCommonPrefix handles single path`() {
        val paths = listOf("/home/user/repo/modules/foo/")
        assertEquals("/home/user/repo/modules/foo/", findCommonPrefix(paths))
    }

    @Test
    fun `findCommonPrefix handles empty list`() {
        assertEquals("", findCommonPrefix(emptyList()))
    }

    @Test
    fun `extractTenant handles nested src directories`() {
        // Captures everything before the first /src/
        assertEquals("project/module", extractTenant("project/module/src/main/src/Schema.graphqls"))
    }

    @Test
    fun `extractTenant returns NO_TENANT for null input`() {
        assertEquals(NO_TENANT, extractTenant(null))
    }

    @Test
    fun `extractTenant returns NO_TENANT for path without src`() {
        assertEquals(NO_TENANT, extractTenant("/random/path/file.graphqls"))
    }

    @Test
    fun `extractTenant returns NO_TENANT for path ending at src`() {
        // The regex requires something after /src/
        assertEquals(NO_TENANT, extractTenant("module/src"))
    }

    @Test
    fun `extractTenant returns NO_TENANT for empty string`() {
        assertEquals(NO_TENANT, extractTenant(""))
    }

    @Test
    fun `extractTenant handles simple module paths`() {
        assertEquals("mymodule", extractTenant("mymodule/src/Schema.graphqls"))
    }

    // ========== TypeDef.tenant extension property tests ==========
    // Note: Extension properties don't strip common prefixes. Use ModulePathComputer for that.

    @Test
    fun `TypeDef tenant returns extracted module path from source location`() {
        val schema = createSchemaWithSourceLocations(
            listOf(
                """
                type Foo {
                    bar: String
                }
                """.trimIndent() to "modules/user/src/graphql/Foo.graphqls"
            ),
            sdlWithNoLocation = """
                type Query { foo: Foo }
            """.trimIndent()
        )

        val fooDef = schema.types["Foo"]!!
        // Extension property returns raw path without prefix stripping
        assertEquals("modules/user", fooDef.tenant)
    }

    @Test
    fun `TypeDef tenant returns NO_TENANT when source location has no src path`() {
        val schema = createSchemaWithSourceLocations(
            listOf(
                """
                type Foo {
                    bar: String
                }
                """.trimIndent() to "/some/random/path/Foo.graphqls"
            ),
            sdlWithNoLocation = """
                type Query { foo: Foo }
            """.trimIndent()
        )

        val fooDef = schema.types["Foo"]!!
        assertEquals(NO_TENANT, fooDef.tenant)
    }

    // ========== Field.fieldTenant extension property tests ==========
    // Note: Extension properties don't strip common prefixes. Use ModulePathComputer for that.

    @Test
    fun `Field fieldTenant returns module path from field's containing extension`() {
        val schema = createSchemaWithSourceLocations(
            listOf(
                """
                type Foo {
                    bar: String
                }
                """.trimIndent() to "modules/user/src/graphql/Foo.graphqls"
            ),
            sdlWithNoLocation = """
                type Query { foo: Foo }
            """.trimIndent()
        )

        val fooDef = schema.types["Foo"] as ViaductSchema.Record
        val barField = fooDef.fields.find { it.name == "bar" }!!
        // Extension property returns raw path without prefix stripping
        assertEquals("modules/user", barField.fieldTenant)
    }

    // ========== Field.inExtension extension property tests ==========

    @Test
    fun `Field inExtension returns false when field and type are in same module`() {
        val schema = createSchemaWithSourceLocations(
            listOf(
                """
                type Foo {
                    bar: String
                }
                """.trimIndent() to "modules/user/src/graphql/Foo.graphqls"
            ),
            sdlWithNoLocation = """
                type Query { foo: Foo }
            """.trimIndent()
        )

        val fooDef = schema.types["Foo"] as ViaductSchema.Record
        val barField = fooDef.fields.find { it.name == "bar" }!!
        assertFalse(barField.inExtension)
    }

    @Test
    fun `Field inExtension returns true when field is in different module than type`() {
        val schema = createSchemaWithSourceLocations(
            listOf(
                """
                type Foo {
                    bar: String
                }
                """.trimIndent() to "modules/user/src/graphql/Foo.graphqls",
                """
                extend type Foo {
                    baz: Int
                }
                """.trimIndent() to "modules/listing/src/graphql/FooExtension.graphqls"
            ),
            sdlWithNoLocation = """
                type Query { foo: Foo }
            """.trimIndent()
        )

        val fooDef = schema.types["Foo"] as ViaductSchema.Record
        val barField = fooDef.fields.find { it.name == "bar" }!!
        val bazField = fooDef.fields.find { it.name == "baz" }!!

        assertFalse(barField.inExtension, "bar should not be in extension (same module)")
        assertTrue(bazField.inExtension, "baz should be in extension (different module)")
    }

    @Test
    fun `Field inExtension returns false when extension is in same module as type`() {
        val schema = createSchemaWithSourceLocations(
            listOf(
                """
                type Foo {
                    bar: String
                }
                """.trimIndent() to "modules/user/src/graphql/Foo.graphqls",
                """
                extend type Foo {
                    baz: Int
                }
                """.trimIndent() to "modules/user/src/graphql/FooExtension.graphqls"
            ),
            sdlWithNoLocation = """
                type Query { foo: Foo }
            """.trimIndent()
        )

        val fooDef = schema.types["Foo"] as ViaductSchema.Record
        val bazField = fooDef.fields.find { it.name == "baz" }!!

        assertFalse(bazField.inExtension, "baz should not be in extension (same module)")
    }

    // ========== Field.hasExternalType extension property tests ==========

    @Test
    fun `Field hasExternalType returns false when field type is in same module`() {
        val schema = createSchemaWithSourceLocations(
            listOf(
                """
                type Foo {
                    bar: Bar
                }
                type Bar {
                    name: String
                }
                """.trimIndent() to "modules/user/src/graphql/Types.graphqls"
            ),
            sdlWithNoLocation = """
                type Query { foo: Foo }
            """.trimIndent()
        )

        val fooDef = schema.types["Foo"] as ViaductSchema.Record
        val barField = fooDef.fields.find { it.name == "bar" }!!

        assertFalse(barField.hasExternalType)
    }

    @Test
    fun `Field hasExternalType returns true when field type is in different module`() {
        val schema = createSchemaWithSourceLocations(
            listOf(
                """
                type Foo {
                    bar: Bar
                }
                """.trimIndent() to "modules/user/src/graphql/Foo.graphqls",
                """
                type Bar {
                    name: String
                }
                """.trimIndent() to "modules/listing/src/graphql/Bar.graphqls"
            ),
            sdlWithNoLocation = """
                type Query { foo: Foo }
            """.trimIndent()
        )

        val fooDef = schema.types["Foo"] as ViaductSchema.Record
        val barField = fooDef.fields.find { it.name == "bar" }!!

        assertTrue(barField.hasExternalType)
    }

    @Test
    fun `Field hasExternalType handles wrapped types correctly`() {
        val schema = createSchemaWithSourceLocations(
            listOf(
                """
                type Foo {
                    bars: [Bar!]!
                }
                """.trimIndent() to "modules/user/src/graphql/Foo.graphqls",
                """
                type Bar {
                    name: String
                }
                """.trimIndent() to "modules/listing/src/graphql/Bar.graphqls"
            ),
            sdlWithNoLocation = """
                type Query { foo: Foo }
            """.trimIndent()
        )

        val fooDef = schema.types["Foo"] as ViaductSchema.Record
        val barsField = fooDef.fields.find { it.name == "bars" }!!

        assertTrue(barsField.hasExternalType, "wrapped type Bar should still be external")
    }
}
