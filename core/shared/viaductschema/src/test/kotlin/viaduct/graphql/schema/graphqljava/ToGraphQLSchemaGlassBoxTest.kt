package viaduct.graphql.schema.graphqljava

import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromGraphQLSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.graphql.schema.test.BUILTIN_SCALARS
import viaduct.graphql.schema.test.SchemaDiff
import viaduct.graphql.schema.test.createSchemaWithSourceLocations

/**
 * Glass-box tests for ToGraphQLSchema conversion.
 *
 * These tests verify implementation-specific behaviors of the toGraphQLSchema method:
 * - additionalScalars parameter handling
 * - Type caching in convertedTypeDefs (regression tests for duplicate type errors)
 * - PassthroughCoercing for custom scalars (regression tests for parseLiteral support)
 * - Source location preservation
 * - Applied directive argument type handling (regression tests for type lookup)
 *
 * Black-box round-trip tests have been moved to the shared TestSchemas infrastructure
 * and are exercised via GJComparisonAndRoundTripTest and BlackBoxToRegistryTest.
 */
class ToGraphQLSchemaGlassBoxTest {
    // ==================== additionalScalars parameter ====================

    @Test
    fun `toGraphQLSchema with additional scalars`() {
        val sdl = """
            scalar Timestamp
            type Query { timestamp: Timestamp }
        """.trimIndent()

        val fullSdl = "$builtins\n$sdl"
        val tdr = SchemaParser().parse(fullSdl)
        val schema = ViaductSchema.fromTypeDefinitionRegistry(tdr)

        val graphqlSchema = schema.toGraphQLSchema(
            scalarsNeeded = setOf("Timestamp"),
            additionalScalars = setOf("CustomScalar", "AnotherCustomScalar")
        )

        org.junit.jupiter.api.Assertions.assertNotNull(graphqlSchema.getType("Timestamp"))
        org.junit.jupiter.api.Assertions.assertNotNull(graphqlSchema.getType("CustomScalar"))
        org.junit.jupiter.api.Assertions.assertNotNull(graphqlSchema.getType("AnotherCustomScalar"))
    }

    @Test
    fun `toGraphQLSchema with only additional scalars no needed scalars`() {
        val sdl = """
            type Query { field: String }
        """.trimIndent()

        val fullSdl = "$builtins\n$sdl"
        val tdr = SchemaParser().parse(fullSdl)
        val schema = ViaductSchema.fromTypeDefinitionRegistry(tdr)

        val graphqlSchema = schema.toGraphQLSchema(
            scalarsNeeded = emptySet(),
            additionalScalars = setOf("CustomScalar1", "CustomScalar2")
        )

        org.junit.jupiter.api.Assertions.assertNotNull(graphqlSchema.getType("CustomScalar1"))
        org.junit.jupiter.api.Assertions.assertNotNull(graphqlSchema.getType("CustomScalar2"))
    }

    // ==================== REGRESSION TESTS: Type caching (convertedTypeDefs) ====================
    // These tests verify that types are properly cached in convertedTypeDefs to avoid
    // duplicate type errors when the same type appears multiple times (e.g., as Query type
    // and in the types map). Without proper caching, graphql-java throws:
    // "You have redefined the type 'X' from being a 'GraphQLObjectType' to a 'GraphQLObjectType'"

    @Test
    fun `Custom-named query type should not cause duplicate type error`() {
        // Regression test: convertObjectType wasn't caching results in convertedTypeDefs.
        // When the query type has a non-standard name (not "Query"), it appears in both
        // the types map AND gets converted via .query(), causing duplicate type errors.
        // The firstPassType filter only excludes literal "Query", "Mutation", "Subscription".
        val sdl = """
            schema { query: MyQueryRoot }
            type MyQueryRoot { foo: String bar: Int }
        """.trimIndent()
        roundTrip(sdl, setOf("Int"))
    }

    @Test
    fun `Custom-named mutation type should not cause duplicate type error`() {
        // Same issue as query type - mutation with non-standard name
        val sdl = """
            schema { query: QueryRoot mutation: MutationRoot }
            type QueryRoot { foo: String }
            type MutationRoot { bar: String baz: Int }
        """.trimIndent()
        roundTrip(sdl, setOf("Int"))
    }

    @Test
    fun `Custom-named subscription type should not cause duplicate type error`() {
        // Same issue as query type - subscription with non-standard name
        val sdl = """
            schema { query: QueryRoot subscription: SubRoot }
            type QueryRoot { foo: String }
            type SubRoot { events: String }
        """.trimIndent()
        roundTrip(sdl)
    }

    @Test
    fun `All custom-named root types together`() {
        // All three root types with custom names
        val sdl = """
            schema { query: Q mutation: M subscription: S }
            type Q { query: String }
            type M { mutate: String }
            type S { subscribe: String }
        """.trimIndent()
        roundTrip(sdl)
    }

    @Test
    fun `Input type used in multiple arguments should be cached`() {
        // Regression test: convertInputObjectType wasn't caching, could cause issues
        // when same input type is referenced from multiple places
        val sdl = """
            input Filter { name: String }
            type Query {
                foo(filter: Filter): String
                bar(filter: Filter): Int
            }
        """.trimIndent()
        roundTrip(sdl, setOf("Int"))
    }

    @Test
    fun `Interface type implemented by multiple objects should be cached`() {
        // Regression test: convertInterfaceType wasn't caching
        val sdl = """
            interface Node { id: ID }
            type Foo implements Node { id: ID name: String }
            type Bar implements Node { id: ID value: Int }
            type Query { node: Node }
        """.trimIndent()
        roundTrip(sdl, setOf("ID", "Int"))
    }

    // ==================== REGRESSION TESTS: PassthroughCoercing for custom scalars ====================
    // These tests verify that custom scalars have proper Coercing implementations that
    // support parseLiteral. Without this, graphql-java validation fails with:
    // "The non deprecated version of parseLiteral has not been implemented by this scalar"

    @Test
    fun `Custom scalar used in directive argument should have proper Coercing`() {
        // Regression test: empty Coercing implementation didn't support parseLiteral,
        // causing validation errors when custom scalars were used in directive arguments
        val sdl = """
            scalar JSON
            directive @config(value: JSON!) on FIELD_DEFINITION
            type Query { foo: String @config(value: "{}") }
        """.trimIndent()
        roundTrip(sdl, setOf("JSON"))
    }

    @Test
    fun `Additional scalar used in directive should work`() {
        // Test that additionalScalars also get proper Coercing
        val sdl = """
            directive @meta(data: String!) on OBJECT
            type Query @meta(data: "test") { foo: String }
        """.trimIndent()
        roundTrip(sdl)
    }

    @Test
    fun `Custom scalar with string default value in argument`() {
        // Ensure parseLiteral handles StringValue
        val sdl = """
            scalar CustomString
            type Query { foo(arg: CustomString = "default"): String }
        """.trimIndent()
        roundTrip(sdl, setOf("CustomString"))
    }

    @Test
    fun `Custom scalar with array value in directive`() {
        // Ensure parseLiteral handles ArrayValue - tests the recursive parseLiteral
        val sdl = """
            directive @tags(values: [String!]!) on OBJECT
            type Query @tags(values: ["a", "b", "c"]) { foo: String }
        """.trimIndent()
        roundTrip(sdl)
    }

    @Test
    fun `Custom scalar with object value in directive`() {
        // Ensure parseLiteral handles ObjectValue - tests the recursive parseLiteral
        val sdl = """
            input Config { enabled: Boolean name: String }
            directive @configure(config: Config!) on OBJECT
            type Query @configure(config: { enabled: true, name: "test" }) { foo: String }
        """.trimIndent()
        roundTrip(sdl)
    }

    // ==================== SOURCE LOCATION TESTS ====================
    // These tests verify that source locations are preserved in toGraphQLSchema.
    // We check that the generated GraphQLSchema has the expected source locations
    // by inspecting the definition.sourceLocation on each type.

    @Test
    fun `Object type with source location`() {
        val sdl = "type Query { foo: String }"
        verifySourceLocationPreserved(sdl, "schema.graphqls", "Query")
    }

    @Test
    fun `Enum type with source location`() {
        val sdl = """
            enum Status { ACTIVE, INACTIVE }
            type Query { status: Status }
        """.trimIndent()
        verifySourceLocationPreserved(sdl, "schema.graphqls", "Status")
    }

    @Test
    fun `Input type with source location`() {
        val sdl = """
            input Foo { bar: Int }
            type Query { foo(arg: Foo): String }
        """.trimIndent()
        verifySourceLocationPreserved(sdl, "schema.graphqls", "Foo", setOf("Int"))
    }

    @Test
    fun `Interface type with source location`() {
        val sdl = """
            interface Node { id: ID }
            type Query { node: Node }
        """.trimIndent()
        verifySourceLocationPreserved(sdl, "schema.graphqls", "Node", setOf("ID"))
    }

    @Test
    fun `Union type with source location`() {
        val sdl = """
            type Foo { foo: String }
            type Bar { bar: String }
            union FooBar = Foo | Bar
            type Query { foobar: FooBar }
        """.trimIndent()
        verifySourceLocationPreserved(sdl, "schema.graphqls", "FooBar")
    }

    @Test
    fun `Custom scalar with source location`() {
        val sdl = """
            scalar DateTime
            type Query { timestamp: DateTime }
        """.trimIndent()
        verifySourceLocationPreserved(sdl, "schema.graphqls", "DateTime", setOf("DateTime"))
    }

    // ==================== REGRESSION TESTS: Applied directive argument types ====================
    // These tests verify that applied directive arguments use the correct types from the
    // directive definition, not just GraphQLString for everything.

    @Test
    fun `Applied directive with list argument type`() {
        // Regression test: convertAppliedDirective was using GraphQLString for all argument types
        // instead of looking up the actual type from the directive definition.
        // This caused validation errors for directives like @scope(to: [String!]!)
        val sdl = """
            directive @scope(to: [String!]!) repeatable on OBJECT
            type Foo @scope(to: ["default"]) { bar: String }
            type Query { foo: Foo }
        """.trimIndent()
        roundTrip(sdl)
    }

    @Test
    fun `Applied directive with non-null list of non-null strings`() {
        // More explicit test for [String!]! argument type
        val sdl = """
            directive @tags(values: [String!]!) on OBJECT
            type Foo @tags(values: ["a", "b", "c"]) { bar: String }
            type Query { foo: Foo }
        """.trimIndent()
        roundTrip(sdl)
    }

    @Test
    fun `Applied directive with Int argument type`() {
        // Test that non-String argument types work correctly
        val sdl = """
            directive @priority(level: Int!) on FIELD_DEFINITION
            type Query { foo: String @priority(level: 5) }
        """.trimIndent()
        roundTrip(sdl, setOf("Int"))
    }

    @Test
    fun `Applied directive with Boolean argument type`() {
        val sdl = """
            directive @feature(enabled: Boolean!) on OBJECT
            type Foo @feature(enabled: true) { bar: String }
            type Query { foo: Foo }
        """.trimIndent()
        roundTrip(sdl)
    }

    @Test
    fun `Applied directive with enum argument type`() {
        val sdl = """
            enum Priority { LOW MEDIUM HIGH }
            directive @importance(level: Priority!) on FIELD_DEFINITION
            type Query { foo: String @importance(level: HIGH) }
        """.trimIndent()
        roundTrip(sdl)
    }

    @Test
    fun `Applied directive with input object argument type`() {
        val sdl = """
            input Config { enabled: Boolean! name: String }
            directive @configure(config: Config!) on OBJECT
            type Foo @configure(config: { enabled: true, name: "test" }) { bar: String }
            type Query { foo: Foo }
        """.trimIndent()
        roundTrip(sdl)
    }

    @Test
    fun `Multiple applied directives with different argument types`() {
        // Test that multiple directives with different argument types all work
        val sdl = """
            directive @scope(to: [String!]!) repeatable on OBJECT
            directive @priority(level: Int!) on OBJECT
            directive @feature(enabled: Boolean!) on OBJECT
            type Foo @scope(to: ["default"]) @priority(level: 10) @feature(enabled: true) { bar: String }
            type Query { foo: Foo }
        """.trimIndent()
        roundTrip(sdl, setOf("Int"))
    }

    companion object {
        private val builtins = """
        """.trimIndent()

        /** The [scalarsNeeded] parameter here deals with strange
         *  behavior of schema builder when it comes to pre-defined
         *  scalars.  If you call
         *  [GraphQLSchema.Builder.additionalType] on a scalar that is
         *  not "used", then you (sometimes) get a "can't define a
         *  type twice" error (because graphql-java adds.  However, if
         *  you "use" it without adding it, you'll get a missing type
         *  error.  I can't figure out the rule for when you do and don't
         *  need to add these types yourself.
         */
        private fun roundTrip(
            sdl: String,
            scalarsNeeded: Set<String> = emptySet(),
        ) {
            val fullSdl = "$builtins\n$sdl"

            // Make GraphQLSchema (expected)
            val tdr = try {
                SchemaParser().parse(fullSdl)
            } catch (e: Exception) {
                throw AssertionError("$fullSdl", e)
            }

            val expectedGraphQLSchema = SchemaGenerator().makeExecutableSchema(tdr, RuntimeWiring.MOCKED_WIRING)

            // turn into a ViaductSchema (GJSchema)
            val expectedViaductSchema = ViaductSchema.fromGraphQLSchema(expectedGraphQLSchema)

            // turn back into a GraphQLSchema (actual)
            val actualGraphQLSchema = try {
                expectedViaductSchema.toGraphQLSchema(scalarsNeeded)
            } catch (e: Exception) {
                val s = expectedViaductSchema.types.toString()
                throw AssertionError("$s", e)
            }

            // turn into a ViaductSchema (GJSchema)
            val actualViaductSchema = ViaductSchema.fromGraphQLSchema(actualGraphQLSchema)

            // Compare using ViaductSchema SchemaDiff (structural comparison)
            val schemaDiffResult = SchemaDiff(expectedViaductSchema, actualViaductSchema).diff()
            schemaDiffResult.assertEmpty()

            // Compare using GraphQLSchemaExtraDiff (runtime properties comparison)
            val extraDiffResult = GraphQLSchemaExtraDiff(expectedGraphQLSchema, actualGraphQLSchema).diff()
            extraDiffResult.assertEmpty()
        }

        /**
         * Verifies that source locations are preserved in toGraphQLSchema conversion.
         *
         * Creates a ViaductSchema with source locations, converts to GraphQLSchema,
         * and verifies the specified type has the expected sourceName in its definition.
         *
         * @param sdl The SDL defining the schema (without built-in scalars)
         * @param sourceName The expected source name
         * @param typeName The type to check for source location
         * @param scalarsNeeded Scalars needed for the conversion
         */
        private fun verifySourceLocationPreserved(
            sdl: String,
            sourceName: String,
            typeName: String,
            scalarsNeeded: Set<String> = emptySet(),
        ) {
            // Create ViaductSchema with source locations
            val viaductSchema: ViaductSchema = try {
                createSchemaWithSourceLocations(
                    listOf(sdl to sourceName),
                    sdlWithNoLocation = BUILTIN_SCALARS
                )
            } catch (e: Exception) {
                throw AssertionError("Failed to parse SDL with source location", e)
            }

            // Convert to GraphQLSchema
            val graphqlSchema = try {
                viaductSchema.toGraphQLSchema(scalarsNeeded)
            } catch (e: Exception) {
                throw AssertionError("Failed to convert to GraphQLSchema: ${viaductSchema.types}", e)
            }

            // Verify the type has the expected source location
            val type = graphqlSchema.getType(typeName)
                ?: throw AssertionError("Type '$typeName' not found in schema")

            val definition = when (type) {
                is graphql.schema.GraphQLObjectType -> type.definition
                is graphql.schema.GraphQLEnumType -> type.definition
                is graphql.schema.GraphQLInputObjectType -> type.definition
                is graphql.schema.GraphQLInterfaceType -> type.definition
                is graphql.schema.GraphQLUnionType -> type.definition
                is graphql.schema.GraphQLScalarType -> type.definition
                else -> throw AssertionError("Unexpected type class: ${type::class}")
            }

            val actualSourceName = (definition as? graphql.language.AbstractDescribedNode<*>)?.sourceLocation?.sourceName
            org.junit.jupiter.api.Assertions.assertEquals(
                sourceName,
                actualSourceName,
                "Source location for type '$typeName' should be '$sourceName'"
            )
        }
    }
}
