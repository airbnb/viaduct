package viaduct.graphql.schema.graphqljava

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class SchemaDefinitionReconcilerTest {
    @TempDir
    lateinit var root: File

    @Test
    fun `empty source collections permit built-in-only assembly`() {
        assertTrue(readTypesFromFiles(emptyList(), root).types().isEmpty())
    }

    @Test
    fun `empty and comment only files do not alter neighboring definitions`() {
        val registry = SchemaDefinitionReconciler.parseSources(
            sequenceOf(
                "empty.graphqls" to " \n",
                "comments.graphqls" to "\uFEFF, # A source containing only comments\r\n\t# Another comment",
                "types.graphqls" to "\n\ntype Query { value: String }",
            )
        )

        val location = registry.types().getValue("Query").sourceLocation
        assertEquals("types.graphqls", location.sourceName)
        assertEquals(3, location.line)
        assertEquals(listOf("Query"), registry.types().keys.toList())
    }

    @Test
    @Timeout(20)
    fun `many named sources preserve ownership coordinates and caller order`() {
        val sources = (1..10000).asSequence().map { index ->
            "schema/module/catalog/source/type$index.graphqls" to "\n\ntype Type$index { value: String }"
        }

        val registry = SchemaDefinitionReconciler.parseSources(sources)

        assertEquals((1..10000).map { "Type$it" }, registry.types().keys.toList())
        registry.types().values.forEachIndexed { index, definition ->
            assertEquals("schema/module/catalog/source/type${index + 1}.graphqls", definition.sourceLocation.sourceName)
            assertEquals(3, definition.sourceLocation.line)
        }
    }

    @Test
    fun `ordinary declarations and extensions retain caller order`() {
        val first = source("z.graphqls", "type Zebra { value: String }\nextend type Query { first: String }")
        val second = source("a.graphqls", "type Query { zebra: Zebra }\nextend type Query { second: String }")

        val registry = readTypesFromFiles(listOf(first, second))

        assertEquals(listOf("Zebra", "Query"), registry.types().keys.toList())
        assertEquals(listOf("first", "second"), registry.objectTypeExtensions().getValue("Query").flatMap { it.fieldDefinitions }.map { it.name })
    }

    @Test
    @Timeout(20)
    fun `many ordinary extensions retain every field in caller order`() {
        val names = (1..10000).map { "field$it" }
        val file = source("schema/module/catalog/source/extensions.graphqls", names.joinToString("\n") { "extend type Query { $it: String }" })

        val registry = readTypesFromFiles(listOf(file), root)

        assertEquals(names, registry.objectTypeExtensions().getValue("Query").flatMap { it.fieldDefinitions }.map { it.name })
    }

    @Test
    fun `large SDL retains the schema parser token limits and source coordinates`() {
        val path = "schema/module/catalog/source/large.graphqls"
        val sdl = "type Large {\n" + (1..6000).joinToString("\n") { "field$it: String" } + "\n}"
        val file = source(path, sdl)

        val registry = readTypesFromFiles(listOf(file), root)
        val field = (registry.types().getValue("Large") as graphql.language.ObjectTypeDefinition).fieldDefinitions.last()

        assertEquals("field6000", field.name)
        assertEquals(path, field.sourceLocation.sourceName)
        assertEquals(6001, field.sourceLocation.line)
    }

    @Test
    fun `generated default winner is independent of input enumeration order`() {
        val first = source("schema/module/alpha/generated/dev.example.defaults/types.graphqls", "\n\ntype Shared { value: String }")
        val second = source("schema/module/beta/generated/dev.example.defaults/types.graphqls", "type Shared { value: String }")

        listOf(listOf(first, second), listOf(second, first)).forEach { files ->
            val winner = readTypesFromFiles(files, root).types().getValue("Shared")
            assertEquals("schema/module/alpha/generated/dev.example.defaults/types.graphqls", winner.sourceLocation.sourceName)
            assertEquals(3, winner.sourceLocation.line)
        }
    }

    @Test
    fun `mixed generated defaults retain developer claim and original source positions`() {
        val developer = source("schema/application/source/schemabase/types.graphqls", "\n\ntype Shared { a: Int b: String }")
        val generated = source("schema/module/catalog/generated/dev.example.defaults/types.graphqls", "type Shared { b: String a: Int }\n\ntype Extra { value: String }")

        val registry = readTypesFromFiles(listOf(generated, developer), root)

        assertEquals("schema/application/source/schemabase/types.graphqls", registry.types().getValue("Shared").sourceLocation.sourceName)
        assertEquals(3, registry.types().getValue("Shared").sourceLocation.line)
        val extra = registry.types().getValue("Extra")
        assertEquals("schema/module/catalog/generated/dev.example.defaults/types.graphqls", extra.sourceLocation.sourceName)
        assertEquals(3, extra.sourceLocation.line)
        assertEquals(1, extra.sourceLocation.column)
        assertEquals("type Shared { b: String a: Int }\n\ntype Extra { value: String }", generated.readText())
    }

    @Test
    fun `custom scalars from distinct suppliers cannot be collapsed`() {
        val first = source("schema/plugin/dev.example.first/source/types.graphqls", "scalar FieldSet")
        val second = source("schema/module/catalog/generated/dev.example.second/types.graphqls", "scalar FieldSet")

        val error = assertThrows<IllegalArgumentException> { readTypesFromFiles(listOf(first, second), root) }

        assertTrue(error.message!!.contains("schema/plugin/dev.example.first/source/types.graphqls"))
        assertTrue(error.message!!.contains("schema/module/catalog/generated/dev.example.second/types.graphqls"))
    }

    @Test
    fun `unchanged bundled definitions retain ordinary duplicate errors`() {
        val first = source("schema/plugin/dev.example.first/source/types.graphqls", "type Shared { value: String }")
        val second = source("schema/plugin/dev.example.second/source/types.graphqls", first.readText())

        assertThrows<IllegalArgumentException> { readTypesFromFiles(listOf(first, second), root) }
    }

    @Test
    fun `explicit roots preserve nested resource names and ignore checkout provenance lookalikes`() {
        val artifactRoot = root.resolve("schema/plugin/checkout/source/build").apply { mkdirs() }
        val name = "schema/module/catalog/source/schema/plugin/other/source/types.graphqls"
        val file = artifactRoot.resolve(name).apply {
            parentFile.mkdirs()
            writeText("scalar Tenant")
        }

        val definition = readTypesFromFiles(listOf(file), artifactRoot).scalars().getValue("Tenant")

        assertEquals(name, definition.sourceLocation.sourceName)
        assertFalse(definition.sourceLocation.sourceName.startsWith(root.path))
    }

    @Test
    fun `malformed plugin sources identify supplier and original line`() {
        listOf("schema/plugin/dev.example.owner/source/bad.graphqls", "schema/module/catalog/generated/dev.example.generator/bad.graphqls").forEach { name ->
            val file = source(name, "\n\ntype Broken { field: }\n")
            val error = assertThrows<IllegalArgumentException> { readTypesFromFiles(listOf(file), root) }
            assertTrue(error.message!!.contains("Internal framework/plugin error"), error.message)
            assertTrue(error.message!!.contains(name), error.message)
            assertTrue(error.message!!.contains(":3:"), error.message)
        }
    }

    @Test
    fun `differing extension member claims identify both files`() {
        val developer = source("schema/module/catalog/source/type.graphqls", "type Query { field: String }")
        val generated = source("schema/module/catalog/generated/dev.example.defaults/extension.graphqls", "extend type Query { field: Int }")
        val error = assertThrows<IllegalArgumentException> { readTypesFromFiles(listOf(developer, generated), root) }
        assertTrue(error.message!!.contains("Query.field"))
        assertTrue(error.message!!.contains("schema/module/catalog/source/type.graphqls"))
        assertTrue(error.message!!.contains("schema/module/catalog/generated/dev.example.defaults/extension.graphqls"))
    }

    @Test
    fun `partially overlapping generated extensions identify both suppliers`() {
        val first = source("schema/module/alpha/generated/dev.first/types.graphqls", "extend type Query { shared: Int a: Int }")
        val second = source("schema/module/beta/generated/dev.second/types.graphqls", "extend type Query { shared: Int b: Int }")

        val error = assertThrows<IllegalArgumentException> { readTypesFromFiles(listOf(first, second), root) }

        assertTrue(error.message!!.contains("Query.shared"), error.message)
        assertTrue(error.message!!.contains("Internal framework/plugin error"), error.message)
        assertTrue(error.message!!.contains("dev.first/types.graphqls:1:"), error.message)
        assertTrue(error.message!!.contains("dev.second/types.graphqls:1:"), error.message)
    }

    @Test
    fun `identical generated extensions collapse before member validation`() {
        val first = source("schema/module/alpha/generated/dev.defaults/types.graphqls", "extend type Query { shared: Int }")
        val second = source("schema/module/beta/generated/dev.defaults/types.graphqls", "extend type Query { shared: Int }")

        val registry = readTypesFromFiles(listOf(second, first), root)

        assertEquals(1, registry.objectTypeExtensions().getValue("Query").size)
        assertEquals("schema/module/alpha/generated/dev.defaults/types.graphqls", registry.objectTypeExtensions().getValue("Query").single().sourceLocation.sourceName)
    }

    @Test
    fun `standard scalar defaults retain legacy deduplication across suppliers`() {
        val developer = source("schema/application/source/schemabase/types.graphqls", "scalar JSON")
        val generated = source("schema/module/catalog/generated/dev.example.defaults/types.graphqls", "scalar JSON")
        assertEquals(1, readTypesFromFiles(listOf(generated, developer), root).scalars().values.count { it.name == "JSON" })
    }

    @Test
    fun `generated directives ignore formatting and argument order`() {
        val developer = source("schema/application/source/schemabase/directives.graphqls", "directive @shared(value: String, limit: Int) on FIELD_DEFINITION")
        val first = source("schema/module/first/generated/dev.example.defaults/directives.graphqls", "# Default\ndirective @shared(limit: Int, value: String) on FIELD_DEFINITION")
        val second = source("schema/module/second/generated/dev.example.defaults/directives.graphqls", first.readText())

        val registry = readTypesFromFiles(listOf(first, second, developer), root)

        assertEquals(1, registry.getDirectiveDefinitions().size)
        assertEquals("schema/application/source/schemabase/directives.graphqls", registry.getDirectiveDefinition("shared").get().sourceLocation.sourceName)
    }

    @Test
    fun `reordered generated fields and arguments deduplicate`() {
        val first = source("schema/module/first/generated/dev.example.defaults/types.graphqls", "type Shared { first(before: Int, after: Int): String second: ID }")
        val second = source("schema/module/second/generated/dev.example.defaults/types.graphqls", "type Shared { second: ID first(after: Int, before: Int): String }")

        assertEquals(1, readTypesFromFiles(listOf(first, second), root).types().size)
    }

    @Test
    fun `generated enum values and union members must preserve declaration order`() {
        val variants = listOf(
            "enum Shared { FIRST SECOND }" to "enum Shared { SECOND FIRST }",
            "union Shared = First | Second" to "union Shared = Second | First",
        )
        variants.forEach { (left, right) ->
            val first = source("schema/module/first/generated/dev.example.defaults/types.graphqls", left)
            val second = source("schema/module/second/generated/dev.example.defaults/types.graphqls", right)

            val error = assertThrows<IllegalArgumentException> { readTypesFromFiles(listOf(first, second), root) }

            assertTrue(error.message!!.contains("schema/module/first/generated/dev.example.defaults/types.graphqls"))
            assertTrue(error.message!!.contains("schema/module/second/generated/dev.example.defaults/types.graphqls"))
        }
    }

    @Test
    fun `matching generated enum and union order still deduplicates`() {
        val sdl = "enum Shared { SECOND FIRST }\nunion Choice = Zebra | Alpha"
        val first = source("schema/module/first/generated/dev.example.defaults/types.graphqls", sdl)
        val second = source("schema/module/second/generated/dev.example.defaults/types.graphqls", sdl)

        val registry = readTypesFromFiles(listOf(second, first), root)

        assertEquals(listOf("SECOND", "FIRST"), (registry.types().getValue("Shared") as graphql.language.EnumTypeDefinition).enumValueDefinitions.map { it.name })
        assertEquals(listOf("Zebra", "Alpha"), (registry.types().getValue("Choice") as graphql.language.UnionTypeDefinition).memberTypes.map { (it as graphql.language.TypeName).name })
    }

    @Test
    fun `additive extensions survive while identical generated extensions deduplicate`() {
        val first =
            source("schema/module/first/generated/dev.example.defaults/extensions.graphqls", "extend type Shared { first(value: ID, limit: Int): String }\nextend schema { mutation: Mutation }")
        val identical = source("schema/module/second/generated/dev.example.defaults/extensions.graphqls", "extend type Shared { first(limit: Int, value: ID): String }")
        val additive = source("schema/module/third/generated/dev.example.defaults/extensions.graphqls", "extend type Shared { second: String }\nextend schema { subscription: Subscription }")

        val registry = readTypesFromFiles(listOf(first, identical, additive), root)

        assertEquals(listOf("first", "second"), registry.objectTypeExtensions().getValue("Shared").flatMap { it.fieldDefinitions }.map { it.name })
        assertEquals(2, registry.schemaExtensionDefinitions.size)
    }

    @Test
    fun `type kinds share a namespace and conflict diagnostics retain both claims`() {
        val first = source("schema/module/first/generated/dev.example.defaults/type.graphqls", "type Shared { value: String }")
        val second = source("schema/module/second/generated/dev.example.defaults/type.graphqls", "enum Shared { VALUE }")

        val error = assertThrows<IllegalArgumentException> { readTypesFromFiles(listOf(first, second), root) }

        assertTrue(error.message!!.contains("'Shared'"))
        assertTrue(error.message!!.contains("schema/module/first/generated/dev.example.defaults/type.graphqls"))
        assertTrue(error.message!!.contains("schema/module/second/generated/dev.example.defaults/type.graphqls"))
    }

    @Test
    fun `directive application order distinguishes generated definitions and members`() {
        val variants = listOf(
            "type Shared @first @second { value: String }" to "type Shared @second @first { value: String }",
            "type Shared @tag(value: 1) @tag(value: 2) { value: String }" to "type Shared @tag(value: 2) @tag(value: 1) { value: String }",
            "extend type Shared { value: String @first @second }" to "extend type Shared { value: String @second @first }",
        )
        variants.forEach { (left, right) ->
            val first = source("schema/module/first/generated/dev.example.defaults/types.graphqls", left)
            val second = source("schema/module/second/generated/dev.example.defaults/types.graphqls", right)

            val error = assertThrows<IllegalArgumentException> { readTypesFromFiles(listOf(first, second), root) }

            assertTrue(error.message!!.contains("schema/module/first/generated/dev.example.defaults/types.graphqls"))
            assertTrue(error.message!!.contains("schema/module/second/generated/dev.example.defaults/types.graphqls"))
        }
    }

    @Test
    fun `matching directive order still permits reordered fields and arguments`() {
        val first = source("schema/module/first/generated/dev.example.defaults/types.graphqls", "type Shared @tag(value: 1) @tag(value: 2) { first(before: Int, after: Int): String second: ID }")
        val second = source("schema/module/second/generated/dev.example.defaults/types.graphqls", "type Shared @tag(value: 1) @tag(value: 2) { second: ID first(after: Int, before: Int): String }")

        assertEquals(1, readTypesFromFiles(listOf(first, second), root).types().size)
    }

    @Test
    fun `directive repeatability descriptions and ordered default lists are significant`() {
        val variants = listOf(
            "directive @shared on OBJECT" to "directive @shared repeatable on OBJECT",
            "\"First\" directive @shared on OBJECT" to "\"Second\" directive @shared on OBJECT",
            "directive @shared(value: [Int] = [1, 2]) on OBJECT" to "directive @shared(value: [Int] = [2, 1]) on OBJECT",
        )
        variants.forEach { (left, right) ->
            val first = source("schema/module/first/generated/dev.example.defaults/directives.graphqls", left)
            val second = source("schema/module/second/generated/dev.example.defaults/directives.graphqls", right)
            assertThrows<IllegalArgumentException> { readTypesFromFiles(listOf(first, second), root) }
        }
    }

    private fun source(
        path: String,
        sdl: String
    ) = root.resolve(path).apply {
        parentFile.mkdirs()
        writeText(sdl)
    }
}
