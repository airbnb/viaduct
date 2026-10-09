package viaduct.graphql.schema.graphqljava

import graphql.GraphQL
import graphql.language.ObjectTypeDefinition
import graphql.parser.MultiSourceReader
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.SchemaPrinter
import graphql.schema.idl.TypeDefinitionRegistry
import graphql.schema.idl.UnExecutableSchemaGenerator
import java.io.StringReader
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
    @Test
    fun `runtime registry preserves source paths on types scalars and extensions`() {
        val reader = MultiSourceReader.newMultiSourceReader().trackData(true)
            .reader(
                StringReader(
                    """
                    scalar Custom
                    type Query { value: Custom }
                    input Input { value: String }
                    enum Choice { FIRST }
                    union Result = Query

                    """.trimIndent()
                ),
                "base.graphqls"
            )
            .reader(
                StringReader(
                    """
                    extend type Query { extra: String }
                    extend input Input { extra: String }
                    extend enum Choice { SECOND }
                    type Other { value: String }
                    extend union Result = Other

                    """.trimIndent()
                ),
                "extension.graphqls"
            )
            .build()
        val schema = gjSchemaRawFromRegistry(reader.use { SchemaParser().parse(it) })

        val registry = schema.toRegistry(TypeDefinitionRegistryOptions.RUNTIME)

        assertEquals("base.graphqls", registry.types().getValue("Query").sourceLocation.sourceName, "Query source")
        assertEquals("base.graphqls", registry.scalars()["Custom"]!!.sourceLocation.sourceName, "Custom scalar source")
        assertEquals("extension.graphqls", registry.objectTypeExtensions()["Query"]!!.single().sourceLocation.sourceName, "Query extension source")
        assertEquals("extension.graphqls", registry.inputObjectTypeExtensions()["Input"]!!.single().sourceLocation.sourceName, "Input extension source")
        assertEquals("extension.graphqls", registry.enumTypeExtensions()["Choice"]!!.single().sourceLocation.sourceName, "Choice extension source")
        assertEquals("extension.graphqls", registry.unionTypeExtensions()["Result"]!!.single().sourceLocation.sourceName, "Result extension source")
    }

    @Test
    fun `compilation registries remain parseable when projected extensions contain only directives`() {
        val registry = SchemaParser().parse(
            """
            directive @tag repeatable on OBJECT | INTERFACE | UNION
            interface Named { name: String }
            interface Item { name: String }
            extend interface Item @tag
            type ConcreteItem implements Item { name: String }
            extend type ConcreteItem implements Named @tag
            union Result = ConcreteItem
            extend union Result @tag
            type Query { item: Item result: Result }
            """.trimIndent()
        )
        val originalSchema = gjSchemaRawFromRegistry(registry)
        val compilationOptions = listOf(
            TypeDefinitionRegistryOptions.DEFAULT to setOf("item", "result", "VIADUCT_IGNORE"),
            TypeDefinitionRegistryOptions.NO_STUBS to setOf("item", "result")
        )
        for ((options, expectedFields) in compilationOptions) {
            val compilationRegistry = originalSchema.toRegistry(options)
            val schema = UnExecutableSchemaGenerator.makeUnExecutableSchema(compilationRegistry)
            val printed = SchemaPrinter(SchemaPrinter.Options.defaultOptions().useAstDefinitions(true)).print(schema)

            val reparsed = SchemaParser().parse(printed)
            val query = reparsed.types()["Query"] as ObjectTypeDefinition
            assertEquals(expectedFields, query.fieldDefinitions.map { it.name }.toSet())
        }
    }

    @Test
    fun `explicit null directive arguments override nonnull defaults`() {
        assertToRegistryRoundTrip(
            """
            directive @tag(value: String = "fallback") on OBJECT
            type Query @tag(value: null) { value: String }
            """.trimIndent()
        )
    }

    @Test
    fun `directive and interface only extensions survive registry conversion`() {
        assertToRegistryRoundTrip(
            """
            directive @tag(value: String) repeatable on OBJECT | INTERFACE | UNION
            interface Item { name: String }
            interface Named { name: String }
            type ConcreteItem { name: String }
            extend type ConcreteItem implements Named @tag(value: "object")
            extend interface Item @tag(value: "interface")
            union Result = ConcreteItem
            extend union Result @tag(value: "union")
            type Query { item: Item result: Result }
            """.trimIndent()
        )
    }

    private fun assertToRegistryRoundTrip(fullSdl: String) {
        val registry = SchemaParser().parse(fullSdl)

        // Create GJSchemaRaw from registry (no graphql-java validation)
        val originalSchema = gjSchemaRawFromRegistry(registry)

        // Convert to TDRegistry
        val roundTrippedRegistry = originalSchema.toRegistry(TypeDefinitionRegistryOptions.RUNTIME)

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
