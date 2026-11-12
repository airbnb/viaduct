package viaduct.gradle.apollo

import graphql.schema.GraphQLTypeUtil.simplePrint
import graphql.schema.GraphQLUnionType
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import graphql.schema.idl.errors.SchemaProblem
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.apiannotations.ExperimentalApi
import viaduct.graphql.scopes.SchemaScopingMode
import viaduct.graphql.scopes.SchemaView
import viaduct.graphql.scopes.ScopedSchemaBuilder

@OptIn(ExperimentalApi::class)
class ApolloSchemaGeneratorTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `schema without entities retains static protocol definitions`() {
        val schema = assembled(generate("type Query { greeting: String }"))
        assertNotNull(schema.getType("_Any"))
        assertNotNull(schema.getObjectType("_Service"))
        assertNotNull(schema.queryType.getFieldDefinition("_service"))
        assertNull(schema.getType("_Entity"))
        assertNull(schema.queryType.getFieldDefinition("_entities"))
    }

    @Test
    fun `entity union merges definitions and extensions across modules without duplicates`() {
        write("schema/plugin/dev.apollo/source/directives.graphqls", DIRECTIVES)
        write("schema/module/z/source/product.graphqls", "type Product @key(fields: \"id\") @key(fields: \"sku\") { id: ID! sku: String }")
        write("schema/module/a/source/user.graphqls", "type User { id: ID! }\nextend type User @key(fields: \"id\", resolvable: true)")
        write("schema/application/source/query.graphqls", "type Query { greeting: String }")
        write("schema/module/a/source/reference.graphqls", "type Reference @key(fields: \"id\", resolvable: false) { id: ID! }")
        val generated = ApolloSchemaGenerator.generate(tempDir.toFile())
        val schema = assembled(generated)

        assertEquals(listOf("Product", "User"), (schema.getType("_Entity") as GraphQLUnionType).types.map { it.name })
        val entities = schema.queryType.getFieldDefinition("_entities")
        assertEquals("[_Entity]!", simplePrint(entities.type))
        assertEquals("[_Any!]!", simplePrint(entities.getArgument("representations").type))
        assertEquals("_Service!", simplePrint(schema.queryType.getFieldDefinition("_service").type))
        assertEquals("String!", simplePrint(requireNotNull(schema.getObjectType("_Service")).getFieldDefinition("sdl").type))
    }

    @Test
    fun `one resolvable key is sufficient and interfaces do not become union members`() {
        val generated = generate(
            """
            type Query { item: Item }
            interface Item @key(fields: "id") { id: ID! }
            type Product implements Item @key(fields: "id", resolvable: false) @key(fields: "sku") { id: ID! sku: String }
            """.trimIndent()
        )
        assertEquals(listOf("Product"), (assembled(generated).getType("_Entity") as GraphQLUnionType).types.map { it.name })
    }

    @Test
    fun `all non-resolvable keys omit the entity protocol`() {
        val generated = generate("type Query { product: Product } type Product @key(fields: \"id\", resolvable: false) { id: ID! }")
        assertNull(assembled(generated).getType("_Entity"))
        assertNotNull(assembled(generated).getType("_Any"))
        assertNull(assembled(generated).queryType.getFieldDefinition("_entities"))
    }

    @Test
    fun `interface keys alone do not create an entity protocol`() {
        val generated = generate("type Query { item: Item } interface Item @key(fields: \"id\") { id: ID! } type Product implements Item { id: ID! }")
        assertNull(assembled(generated).getType("_Entity"))
        assertNull(assembled(generated).queryType.getFieldDefinition("_entities"))
    }

    @Test
    fun `interface extension keys are validated without creating entities`() {
        generate("type Query { item: Item } interface Item { id: ID! } type Product implements Item { id: ID! }")
        val extension = write("interface-extension.graphqls", "extend interface Item @key(fields: \"id\")")
        val schema = assembled(ApolloSchemaGenerator.generate(tempDir.toFile()))
        assertNull(schema.getType("_Entity"))
        assertNull(schema.queryType.getFieldDefinition("_entities"))

        extension.writeText("extend interface Item @key(fields: 1)")
        val failure = assertThrows<IllegalArgumentException> { ApolloSchemaGenerator.generate(tempDir.toFile()) }
        assertTrue(failure.message!!.contains("interface-extension.graphqls"), failure.message)
    }

    @Test
    fun `explicit query roots are preserved including schema extensions`() {
        val generated = generate("schema { query: Shop } type Shop { product: Product } type Product @key(fields: \"id\") { id: ID! }")
        assertEquals("Shop", assembled(generated).queryType.name)
        assertNotNull(assembled(generated).queryType.getFieldDefinition("_entities"))
        tempDir.resolve("schema.graphqls").toFile().writeText("extend schema { query: Shop } type Shop { greeting: String }")
        assertTrue(ApolloSchemaGenerator.generate(tempDir.toFile()).contains("extend type Shop { _service:"))
    }

    @Test
    fun `source order and file creation order do not change entity membership`() {
        val first = "type Z @key(fields: \"id\") { id: ID! }\n"
        val second = "type A @key(fields: \"id\") { id: ID! } type Query { z: Z }"
        write("z.graphqls", first)
        write("a.graphqls", DIRECTIVES + second)
        val generated = ApolloSchemaGenerator.generate(tempDir.toFile())
        assertEquals(listOf("A", "Z"), (assembled(generated).getType("_Entity") as GraphQLUnionType).types.map { it.name })
        tempDir.resolve("z.graphqls").toFile().delete()
        tempDir.resolve("a.graphqls").toFile().delete()
        write("z.graphqls", second)
        write("a.graphqls", DIRECTIVES + first)
        assertEquals(generated, ApolloSchemaGenerator.generate(tempDir.toFile()))
    }

    @Test
    fun `unicode root scopes survive generation from UTF-8 schema files`() {
        val schema = assembled(
            generate(
                """
                directive @scope(to: [String!]!) repeatable on OBJECT | INTERFACE | UNION
                type Query @scope(to: ["café"]) { greeting: String }
                """.trimIndent()
            )
        )
        val scope = requireNotNull(schema.getObjectType("_Service")!!.getAppliedDirective("scope").getArgument("to"))
        assertEquals(listOf("café"), scope.getValue<List<String>>())
    }

    @Test
    fun `removed entities disappear on the next generation`() {
        generate("type Query { greeting: String }")
        val entity = write("schema/module/catalog/source/product.graphqls", "type Product @key(fields: \"id\") { id: ID! }")
        assertNotNull(assembled(ApolloSchemaGenerator.generate(tempDir.toFile())).getType("_Entity"))
        entity.delete()
        assertNull(assembled(ApolloSchemaGenerator.generate(tempDir.toFile())).getType("_Entity"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["fields: 1", "fields: id", "fields: null", "fields: []", "fields: \"\"", "fields: \"id\", resolvable: null", "fields: \"id\", resolvable: \"false\""])
    fun `malformed key literals report their source`(arguments: String) {
        write("schema/plugin/dev.apollo/source/directives.graphqls", DIRECTIVES)
        write("schema/application/source/query.graphqls", "type Query { greeting: String }")
        write("schema/module/catalog/source/product.graphqls", "type Product @key($arguments) { id: ID! }")
        val failure = assertThrows<IllegalArgumentException> { ApolloSchemaGenerator.generate(tempDir.toFile()) }
        assertTrue(failure.message!!.contains("schema/module/catalog/source/product.graphqls"), failure.message)
    }

    @Test
    fun `Foundation key declarations are not interpreted as Apollo declarations`() {
        write("schema.graphqls", "scalar FieldSelectionSet directive @key(fields: FieldSelectionSet!) repeatable on OBJECT | INTERFACE type Query { greeting: String }")
        val failure = assertThrows<IllegalArgumentException> { ApolloSchemaGenerator.generate(tempDir.toFile()) }
        assertTrue(failure.message!!.contains("other federation dialects are unsupported"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["FieldSet", "[FieldSet!]!", "Input!", "Choice!"])
    fun `unsupported key fields declarations fail generation`(fieldsType: String) {
        write(
            "schema.graphqls",
            "scalar FieldSet input Input { id: ID! } enum Choice { ID } directive @key(fields: $fieldsType, resolvable: Boolean = true) repeatable on OBJECT type Query { greeting: String }"
        )
        val failure = assertThrows<IllegalArgumentException> { ApolloSchemaGenerator.generate(tempDir.toFile()) }
        assertTrue(failure.message!!.contains("non-null scalar"), failure.message)
    }

    @Test
    fun `namespaced or aliased keys use an explicit directive name`() {
        write("schema.graphqls", DIRECTIVES.replace("@key", "@apollo__key") + "type Query { value: String } type Product @apollo__key(fields: \"id\") { id: ID! }")
        val generated = ApolloSchemaGenerator.generate(tempDir.toFile(), "apollo__key")
        assertEquals(listOf("Product"), (assembled(generated).getType("_Entity") as GraphQLUnionType).types.map { it.name })
    }

    @ParameterizedTest
    @ValueSource(strings = ["key", "apollo__key"])
    fun `selected key declaration is required even without entities`(keyDirectiveName: String) {
        write("schema.graphqls", "type Query { greeting: String }")
        val failure = assertThrows<IllegalArgumentException> { ApolloSchemaGenerator.generate(tempDir.toFile(), keyDirectiveName) }
        assertTrue(failure.message!!.contains("declaration for selected key directive '@$keyDirectiveName'"), failure.message)
    }

    @ParameterizedTest
    @ValueSource(strings = ["key", "misspelled_key"])
    fun `unselected aliased keys fail instead of silently losing entities`(keyDirectiveName: String) {
        write("schema.graphqls", DIRECTIVES.replace("@key", "@apollo__key") + "type Query { value: String } type Product @apollo__key(fields: \"id\") { id: ID! }")
        val failure = assertThrows<IllegalArgumentException> { ApolloSchemaGenerator.generate(tempDir.toFile(), keyDirectiveName) }
        assertTrue(failure.message!!.contains("declaration for selected key directive '@$keyDirectiveName'"), failure.message)
    }

    @Test
    fun `protocol definition and root field collisions fail schema assembly`() {
        generate("type Query { greeting: String } type _Service { own: String }")
        assertThrows<SchemaProblem> { assembled(ApolloSchemaGenerator.generate(tempDir.toFile())) }
        tempDir.resolve("schema.graphqls").toFile().writeText("type Query { _service: String }")
        assertThrows<SchemaProblem> { assembled(ApolloSchemaGenerator.generate(tempDir.toFile())) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["\"catalog\", \"users\", \"empty\"", "\"*\""])
    fun `scoped projections retain only visible entities and omit the empty entity protocol`(rootScopes: String) {
        val generated = generate(
            """
            directive @scope(to: [String!]!) repeatable on OBJECT | INTERFACE | UNION
            type Query @scope(to: [$rootScopes]) { greeting: String }
            type Product @scope(to: ["catalog"]) @key(fields: "id") { id: ID! }
            type User @scope(to: ["users"]) @key(fields: "id") { id: ID! }
            """.trimIndent()
        )
        val builder = ScopedSchemaBuilder(assembled(generated), SchemaScopingMode.ScopeAware(setOf("catalog", "users", "empty")), emptyList())
        val catalog = builder.build(SchemaView.Scoped(setOf("catalog"))).filtered
        assertEquals(listOf("Product"), (catalog.getType("_Entity") as GraphQLUnionType).types.map { it.name })
        assertNotNull(catalog.getType("_Any"))
        val users = builder.build(SchemaView.Scoped(setOf("users"))).filtered
        assertEquals(listOf("User"), (users.getType("_Entity") as GraphQLUnionType).types.map { it.name })
        assertNotNull(users.getType("_Any"))
        val empty = builder.build(SchemaView.Scoped(setOf("empty"))).filtered
        assertNull(empty.getType("_Entity"))
        assertNull(empty.queryType.getFieldDefinition("_entities"))
        assertNotNull(empty.queryType.getFieldDefinition("_service"))
        assertNull(empty.getType("_Any"))
    }

    @Test
    fun `entity union includes entities outside root scopes and projects their membership`() {
        val generated = generate(
            """
            directive @scope(to: [String!]!) repeatable on OBJECT | INTERFACE | UNION
            schema { query: Shop }
            type Shop @scope(to: ["catalog"]) { greeting: String }
            type Product @scope(to: ["catalog"]) @key(fields: "id") { id: ID! }
            type User @scope(to: ["users"]) @key(fields: "id") { id: ID! }
            """.trimIndent()
        )
        val builder = ScopedSchemaBuilder(assembled(generated), SchemaScopingMode.ScopeAware(setOf("catalog", "users")), emptyList())
        val full = builder.build(SchemaView.Scoped(setOf("catalog", "users"))).filtered
        assertEquals(listOf("Product", "User"), (full.getType("_Entity") as GraphQLUnionType).types.map { it.name })
        val catalog = builder.build(SchemaView.Scoped(setOf("catalog"))).filtered
        assertEquals(listOf("Product"), (catalog.getType("_Entity") as GraphQLUnionType).types.map { it.name })
        assertNotNull(catalog.queryType.getFieldDefinition("_entities"))
    }

    @Test
    fun `only disjoint entities leave the root-only view with the service protocol`() {
        val generated = generate(
            """
            directive @scope(to: [String!]!) repeatable on OBJECT | INTERFACE | UNION
            type Query @scope(to: ["catalog"]) { greeting: String }
            type User @scope(to: ["users"]) @key(fields: "id") { id: ID! }
            """.trimIndent()
        )
        val builder = ScopedSchemaBuilder(assembled(generated), SchemaScopingMode.ScopeAware(setOf("catalog", "users")), emptyList())
        val full = builder.build(SchemaView.Scoped(setOf("catalog", "users"))).filtered
        assertEquals(listOf("User"), (full.getType("_Entity") as GraphQLUnionType).types.map { it.name })
        val catalog = builder.build(SchemaView.Scoped(setOf("catalog"))).filtered
        assertNull(catalog.getType("_Entity"))
        assertNull(catalog.queryType.getFieldDefinition("_entities"))
        assertNotNull(catalog.queryType.getFieldDefinition("_service"))
    }

    private fun generate(sdl: String): String {
        write("directives.graphqls", DIRECTIVES)
        write("schema.graphqls", sdl)
        return ApolloSchemaGenerator.generate(tempDir.toFile())
    }

    private fun assembled(generated: String) =
        UnExecutableSchemaGenerator.makeUnExecutableSchema(
            SchemaParser().parse(tempDir.toFile().walkTopDown().filter { it.isFile && it.extension == "graphqls" }.joinToString("\n") { it.readText() } + "\n" + generated)
        )

    private fun write(
        path: String,
        text: String
    ) = tempDir.resolve(path).toFile().apply {
        parentFile.mkdirs()
        writeText(text, Charsets.UTF_8)
    }

    companion object {
        private const val DIRECTIVES = "scalar FieldSet\ndirective @key(fields: FieldSet!, resolvable: Boolean = true) repeatable on OBJECT | INTERFACE\ndirective @resolver on FIELD_DEFINITION\n"
    }
}
