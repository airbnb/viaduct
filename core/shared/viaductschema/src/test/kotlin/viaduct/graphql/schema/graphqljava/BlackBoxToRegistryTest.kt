package viaduct.graphql.schema.graphqljava

import graphql.GraphQL
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeDefinitionRegistry
import graphql.schema.idl.UnExecutableSchemaGenerator
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import viaduct.graphql.schema.checkViaductSchemaInvariants
import viaduct.graphql.schema.graphqljava.extensions.TypeDefinitionRegistryOptions
import viaduct.graphql.schema.graphqljava.extensions.toRegistry
import viaduct.graphql.schema.graphqljava.extensions.toRegistryWithoutExtensionTypeDefinitions
import viaduct.graphql.schema.test.SchemaDiff
import viaduct.graphql.schema.test.TestSchemas
import viaduct.invariants.FailureCollector

/**
 * Black-box tests for toRegistry round-trip.
 *
 * These tests verify that ViaductSchema.toRegistry() preserves schema semantics:
 * SDL → TypeDefinitionRegistry → GJSchemaRaw → toRegistry → GJSchemaRaw → Compare
 *
 * Uses GJSchemaRaw exclusively to avoid graphql-java validation bugs that would
 * incorrectly reject valid GraphQL SDL (e.g., empty union base definitions,
 * interface implementation via extension).
 *
 * Tests are grouped by GraphQL definition kind - each kind runs as one test that
 * exercises all schemas of that kind.
 */
class BlackBoxToRegistryTest {
    private fun assertToRegistryRoundTrip(fullSdl: String) {
        val registry = SchemaParser().parse(fullSdl)

        // Create GJSchemaRaw from registry (no graphql-java validation)
        val originalSchema = gjSchemaRawFromRegistry(registry)

        // Convert to TDRegistry
        val roundTrippedRegistry = originalSchema.toRegistry(TypeDefinitionRegistryOptions.NO_STUBS)

        // Create GJSchemaRaw from round-tripped registry
        val roundTrippedSchema = gjSchemaRawFromRegistry(roundTrippedRegistry)

        // Compare original vs round-tripped
        val checker = FailureCollector()
        checkViaductSchemaInvariants(originalSchema, checker)
        checkViaductSchemaInvariants(roundTrippedSchema, checker)
        SchemaDiff(originalSchema, roundTrippedSchema, checker).diff()
        checker.assertEmpty("\n")
    }

    @Test
    fun `registry conversions preserve descriptions in introspection`() {
        val original = SchemaParser().parse(
            """
            "Directive description"
            directive @custom("Directive argument" value: String) on FIELD_DEFINITION
            "Scalar description"
            scalar CustomScalar
            "Object description"
            type Query {
                "Field description"
                item("Field argument" input: Input): Item
                undocumented: String
            }
            extend type Query { "Extended field" extra: String }
            "Interface description"
            interface Item { "Interface field" name: String }
            extend interface Item { "Extended interface field" extra: String }
            type ConcreteItem implements Item { name: String extra: String }
            "Input description"
            input Input { "Input field" value: String }
            extend input Input { "Extended input field" extra: String }
            "Enum description"
            enum Status { "Enum value" ACTIVE }
            extend enum Status { "Extended enum value" INACTIVE }
            "Union description"
            union Result = ConcreteItem
            """.trimIndent()
        )
        val schema = gjSchemaRawFromRegistry(original)
        val expected = introspectDescriptions(original)

        assertEquals(expected, introspectDescriptions(schema.toRegistry(TypeDefinitionRegistryOptions.NO_STUBS)))
        assertEquals(expected, introspectDescriptions(schema.toRegistryWithoutExtensionTypeDefinitions(TypeDefinitionRegistryOptions.NO_STUBS)))
    }

    private fun introspectDescriptions(registry: TypeDefinitionRegistry): Map<String, Any?> {
        val queries = listOf("Query", "Item", "Input", "Status", "Result", "CustomScalar").associateWith { name ->
            """
            { __type(name: "$name") {
                description
                fields { name description args { name description } }
                inputFields { name description }
                enumValues { name description }
            } }
            """.trimIndent()
        } + ("directives" to "{ __schema { directives { name description args { name description } } } }")
        val graphQL = GraphQL.newGraphQL(UnExecutableSchemaGenerator.makeUnExecutableSchema(registry)).build()
        return queries.mapValues { (_, query) ->
            val result = graphQL.execute(query)
            assertEquals(emptyList<Any>(), result.errors)
            result.getData<Map<String, Any?>>()
        }
    }

    @Test
    @DisplayName("DIRECTIVE schemas")
    fun `toRegistry round-trip for directive schemas`() {
        assertAll(
            TestSchemas.DIRECTIVE.map { schema ->
                Executable { assertToRegistryRoundTrip(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("ENUM schemas")
    fun `toRegistry round-trip for enum schemas`() {
        assertAll(
            TestSchemas.ENUM.map { schema ->
                Executable { assertToRegistryRoundTrip(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("INPUT schemas")
    fun `toRegistry round-trip for input schemas`() {
        assertAll(
            TestSchemas.INPUT.map { schema ->
                Executable { assertToRegistryRoundTrip(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("INTERFACE schemas")
    fun `toRegistry round-trip for interface schemas`() {
        assertAll(
            TestSchemas.INTERFACE.map { schema ->
                Executable { assertToRegistryRoundTrip(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("OBJECT schemas")
    fun `toRegistry round-trip for object schemas`() {
        assertAll(
            TestSchemas.OBJECT.map { schema ->
                Executable { assertToRegistryRoundTrip(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("SCALAR schemas")
    fun `toRegistry round-trip for scalar schemas`() {
        assertAll(
            TestSchemas.SCALAR.map { schema ->
                Executable { assertToRegistryRoundTrip(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("UNION schemas")
    fun `toRegistry round-trip for union schemas`() {
        assertAll(
            TestSchemas.UNION.map { schema ->
                Executable { assertToRegistryRoundTrip(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("ROOT schemas")
    fun `toRegistry round-trip for root schemas`() {
        assertAll(
            TestSchemas.ROOT.map { schema ->
                Executable { assertToRegistryRoundTrip(schema.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("COMPLEX schemas")
    fun `toRegistry round-trip for complex schemas`() {
        assertAll(
            TestSchemas.COMPLEX.map { schema ->
                Executable { assertToRegistryRoundTrip(schema.fullSdl) }
            }
        )
    }
}
