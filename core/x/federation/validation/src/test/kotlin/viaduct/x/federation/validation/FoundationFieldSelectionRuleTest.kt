package viaduct.x.federation.validation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.graphql.schema.validation.SchemaValidationError
import viaduct.graphql.schema.validation.ValidationContext

class FoundationFieldSelectionRuleTest {
    @Test
    fun `keys support repeatable and nested selections on objects and interfaces`() {
        val errors = validate(
            """
            interface Node @key(fields: "id") { id: ID! }
            type Product implements Node @key(fields: "id") @key(fields: "sku variation { size }") {
                id: ID!
                sku: String!
                variation: Variation!
            }
            type Variation { size: Size! }
            enum Size { SMALL LARGE }
            """,
        )
        assertEquals(emptyList<SchemaValidationError>(), errors)
    }

    @Test
    fun `aliases resolve the underlying source field at every nesting level`() {
        val errors = validate(
            """
            interface Node @key(fields: "kind: __typename nodeId: id") { id: ID! }
            type Product implements Node @key(fields: "productId: id option: variation { dimension: size }") {
                id: ID!
                variation: Variation @external
            }
            type Variation { size: String @external }
            type Query { product: Product @provides(fields: "option: variation { dimension: size }") }
            """,
        )
        assertEquals(emptyList<SchemaValidationError>(), errors)
    }

    @Test
    fun `aliases do not make an undefined source field valid`() {
        val errors = validate(
            """
            type Product @key(fields: "id: missing") { id: ID! @external }
            type Query { product: Product @provides(fields: "id: missing") }
            """,
        )
        assertEquals(listOf("KEY_INVALID_FIELDS", "PROVIDES_INVALID_FIELDS"), errors.map { it.code }.sorted())
        assertTrue(errors.all { "Product.missing" in it.message })
    }

    @ParameterizedTest
    @ValueSource(strings = ["__typename id", "kind: __typename id", "variation { __typename size }"])
    fun `keys and interface provides accept implicit typename selections`(fields: String) {
        val errors = validate(
            """
            type Product @key(fields: "$fields") { id: ID! @external variation: Variation @external }
            type Variation { size: String @external }
            interface Provider { product: Product @provides(fields: "$fields") }
            """,
        )
        assertEquals(emptyList<SchemaValidationError>(), errors)
    }

    @Test
    fun `object provides applies external requirements to implicit typename at every level`() {
        val errors = validate(
            """
            type Query { product: Product @provides(fields: "__typename name variation { kind: __typename size }") }
            type Product { name: String @external variation: Variation @external }
            type Variation { size: String @external }
            """,
        )
        assertEquals(listOf("PROVIDES_FIELDS_MISSING_EXTERNAL", "PROVIDES_FIELDS_MISSING_EXTERNAL"), errors.map { it.code })
        assertTrue("Product.__typename" in errors[0].message)
        assertTrue("Variation.__typename" in errors[1].message)
        assertTrue(errors.all { "implicit and cannot be marked @external" in it.message })
    }

    @Test
    fun `implicit typename remains a scalar and cannot have nested selections`() {
        val errors = validate(
            """
            type Product @key(fields: "kind: __typename { id }") { id: ID! }
            interface Provider { product: Product @provides(fields: "__typename { id }") }
            """,
        )
        assertEquals(listOf("KEY_INVALID_FIELDS", "PROVIDES_INVALID_FIELDS"), errors.map { it.code }.sorted())
        assertTrue(errors.all { "scalar type String!" in it.message })
    }

    @ParameterizedTest
    @ValueSource(strings = ["123", "true", "[\"id\"]", "{ name: \"id\" }", "id", "null"])
    fun `key and provides reject every nonstring literal kind`(literal: String) {
        val errors = validate("type Product @key(fields: $literal) { id: ID! } type Query { product: Product @provides(fields: $literal) }")
        assertEquals(listOf("KEY_INVALID_FIELDS_TYPE", "PROVIDES_INVALID_FIELDS_TYPE"), errors.map { it.code }.sorted())
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "id {", "id } query Other { id", "id } fragment Other on Product { id", "{ id }"])
    fun `key and provides reject malformed empty and injected selections`(fields: String) {
        val errors = validate("type Product @key(fields: \"$fields\") { id: ID! } type Query { product: Product @provides(fields: \"$fields\") }")
        assertEquals(listOf("KEY_INVALID_SYNTAX", "PROVIDES_INVALID_SYNTAX"), errors.map { it.code }.sorted())
    }

    @Test
    fun `references resolve recursively against the selected field return type`() {
        val errors = validate(
            """
            type Product @key(fields: "variation { missing }") { variation: Variation }
            type Variation { size: String @external }
            type Query { product: Product @provides(fields: "variation { missing }") }
            """,
        )
        assertTrue(errors.any { it.code == "KEY_INVALID_FIELDS" && "Variation.missing" in it.message })
        assertTrue(errors.any { it.code == "PROVIDES_INVALID_FIELDS" && "Variation.missing" in it.message })
    }

    @ParameterizedTest
    @ValueSource(strings = ["[ID!]!", "[[ID]]", "Node!", "Result!"])
    fun `keys reject list interface and union fields after unwrapping nonnull`(type: String) {
        val errors = validate(
            """
            type Product @key(fields: "value") { value: $type }
            interface Node { id: ID! }
            type Item implements Node { id: ID! }
            union Result = Item
            """,
        )
        assertTrue(errors.any { it.code == "KEY_FIELDS_SELECT_INVALID_TYPE" })
    }

    @Test
    fun `key argument rules allow optional defaulted and require supplied values`() {
        val errors = validate(
            """
            type Product @key(fields: "optional defaulted requiredByExecutor") {
                optional(locale: String): ID!
                defaulted(locale: String! = "en"): ID!
                requiredByExecutor(locale: String! @require(field: "locale")): ID!
            }
            """,
        )
        assertEquals(emptyList<SchemaValidationError>(), errors)
        assertEquals("KEY_FIELDS_HAS_ARGUMENTS", validate("type Product @key(fields: \"id\") { id(locale: String!): ID! }").single().code)
    }

    @Test
    fun `embedded arguments and directives are rejected even under invalid references`() {
        val errors = validate(
            """
            type Product @key(fields: "missing { id(locale: 1) @skip(if: true) }") { id: ID! }
            type Query { product: Product @provides(fields: "id(locale: 1) @skip(if: true)") }
            """,
        )
        assertTrue(errors.map { it.code }.containsAll(listOf("KEY_FIELDS_HAS_ARGUMENTS", "KEY_DIRECTIVE_IN_FIELDS_ARGUMENT", "PROVIDES_FIELDS_HAS_ARGUMENTS", "PROVIDES_DIRECTIVE_IN_FIELDS_ARGUMENT")))
    }

    @Test
    fun `provides accepts wrapped objects interfaces and nested external fields`() {
        val errors = validate(
            """
            type Query {
                products: [[Product!]!]! @provides(fields: "variation { sizes }")
                node: Node @provides(fields: "id ... on Product { variation { sizes } }")
            }
            interface Node { id: ID! @external }
            type Product implements Node { id: ID! @external variation: Variation @external }
            type Variation { sizes(locale: String @require(field: "locale")): [String] @external }
            """,
        )
        assertEquals(emptyList<SchemaValidationError>(), errors)
    }

    @Test
    fun `provides requires external at every nested field`() {
        val errors = validate(
            """
            type Query { product: Product @provides(fields: "variation { size }") }
            type Product { variation: Variation }
            type Variation { size: String }
            """,
        )
        assertEquals(listOf("PROVIDES_FIELDS_MISSING_EXTERNAL", "PROVIDES_FIELDS_MISSING_EXTERNAL"), errors.map { it.code })
        assertTrue(errors[0].message.contains("Product.variation"))
        assertTrue(errors[1].message.contains("Variation.size"))
    }

    @Test
    fun `external requirement applies to provides on object fields`() {
        val errors = validate("interface Provider { product: Product @provides(fields: \"id\") } type Product { id: ID! }")
        assertEquals(emptyList<SchemaValidationError>(), errors)
    }

    @Test
    fun `provides validates nested union selections through applicable fragments`() {
        val errors = validate(
            """
            type Query { product: Product @provides(fields: "related { ... on Item { id } }") }
            type Product { related: [Related!]! @external }
            union Related = Item
            type Item { id: ID! @external }
            """,
        )
        assertEquals(emptyList<SchemaValidationError>(), errors)
    }

    @Test
    fun `nested key fields receive the same type and argument checks`() {
        val errors = validate(
            """
            type Product @key(fields: "variation { sizes code }") { variation: Variation }
            type Variation { sizes: [String] code(locale: String!): ID }
            """,
        )
        assertEquals(listOf("KEY_FIELDS_SELECT_INVALID_TYPE", "KEY_FIELDS_HAS_ARGUMENTS"), errors.map { it.code })
    }

    @Test
    fun `inline fragment directives and invalid conditions are diagnosed`() {
        val errors = validate(
            """
            type Product @key(fields: "... @skip(if: true) { id } ... on Missing { id }") { id: ID! }
            """,
        )
        assertEquals(listOf("KEY_DIRECTIVE_IN_FIELDS_ARGUMENT", "KEY_INVALID_FIELDS"), errors.map { it.code })
    }

    @ParameterizedTest
    @ValueSource(strings = ["String", "Size!", "[String!]!", "Result"])
    fun `provides rejects nonobject and noninterface return types`(type: String) {
        val errors = validate("type Query { value: $type @provides(fields: \"id\") } enum Size { SMALL } type Item { id: ID } union Result = Item")
        assertEquals("PROVIDES_ON_NON_COMPOSITE_FIELD", errors.single().code)
    }

    @ParameterizedTest
    @ValueSource(strings = ["String", "String = \"en\"", "String!", "String! = \"en\""])
    fun `provides rejects declared arguments even when optional or defaulted`(argumentType: String) {
        val errors = validate("type Query { product: Product @provides(fields: \"id\") } type Product { id(locale: $argumentType): ID! @external }")
        assertEquals("PROVIDES_FIELDS_HAS_ARGUMENTS", errors.single().code)
    }

    @Test
    fun `nested selections must match field shapes and fragment conditions`() {
        val errors = validate(
            """
            type Product @key(fields: "variation id { missing } ...Missing ... on Other { id }") { id: ID! variation: Variation }
            type Variation { size: String }
            type Other { id: ID! }
            type Query { product: Product @provides(fields: "variation") }
            """,
        )
        assertEquals(4, errors.count { it.code == "KEY_INVALID_FIELDS" })
        assertTrue(errors.any { it.code == "PROVIDES_INVALID_FIELDS" })
    }

    private fun validate(body: String): List<SchemaValidationError> {
        val schema = ViaductSchema.fromTypeDefinitionRegistry(
            """
            scalar FieldSelectionSet
            scalar FieldSelectionMap
            directive @key(fields: FieldSelectionSet!) repeatable on OBJECT | INTERFACE
            directive @provides(fields: FieldSelectionSet!) on FIELD_DEFINITION
            directive @external on FIELD_DEFINITION
            directive @require(field: FieldSelectionMap!) on ARGUMENT_DEFINITION
            $body
            """.trimIndent(),
        )
        return ValidationContext(schema).apply { walkSchema(listOf(FoundationFieldSelectionRule())) }.errors
    }
}
