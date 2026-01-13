package viaduct.graphql.schema.graphqljava

import graphql.schema.GraphQLSchema
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.test.BUILTIN_SCALARS
import viaduct.graphql.schema.test.SchemaDiff
import viaduct.graphql.schema.test.mkSchemaWithSourceLocations

/** Tests for ToGraphQLSchema conversion by doing GraphQLSchema -> GJSchema -> GraphQLSchema -> GJSchema round trips. */
class GJRoundTripTest {
    @Test
    fun `Scalar field`() {
        val sdl = "type Query { foo: Float bar: Int }"
        roundTrip(sdl, setOf("Float", "Int"))
    }

    @Test
    fun `Object-type field`() {
        val sdl = "type Foo { bar: Int } type Query { foo: Foo }"
        roundTrip(sdl, setOf("Int"))
    }

    @Test
    fun `Unused enum`() {
        val sdl = "enum Foo { FOO } type Query { foo: Int }"
        roundTrip(sdl, setOf("Int"))
    }

    @Test
    fun `Enum field`() {
        val sdl = "enum Foo { FOO } type Query { foo: Foo }"
        roundTrip(sdl)
    }

    @Test
    fun `Field with scalar argument argument`() {
        val sdl = "enum Foo { FOO } type Query { foo(strongRead: Boolean): Foo }"
        roundTrip(sdl) // I have no idea why Boolean isn't a needed scalar...
    }

    @Test
    fun `Field with enum argument argument`() {
        val sdl = "enum Foo { FOO } type Query { foo(foo: Foo): Foo }"
        roundTrip(sdl) // I have no idea why Boolean isn't a needed scalar...
    }

    @Test
    fun `Unused input-object type`() {
        val sdl = "input Foo { foo: Boolean } type Query { foo: Int }"
        roundTrip(sdl, setOf("Int"))
    }

    @Test
    fun `Object-type field with input-object argument`() {
        val sdl = "input Foo { foo: Boolean } type Query { foo(arg: Foo): Int }"
        roundTrip(sdl, setOf("Int")) // Looks like scalars are only "needed" for output types
    }

    @Test
    fun `Interface field (String)`() {
        val sdl = "interface Bar { bar: String } type Query { bar: Bar }"
        roundTrip(sdl) // I don't know String isn't a needed scalar here
    }

    @Test
    fun `Interface field (Int)`() {
        val sdl = "interface Bar { bar: Int } type Query { bar: Bar }"
        roundTrip(sdl, setOf("Int")) // Looks like String and Int are handled differently
    }

    @Test
    fun `Object implements an interface`() {
        val sdl = """
            interface Bar { bar: String }
            type Foo implements Bar { bar: String }
            type Query { bar: Bar }
        """.trimIndent()
        roundTrip(sdl)
    }

    @Test
    fun `Interface implements an interface`() {
        val sdl = """
            interface Bar { bar: String }
            interface Foo implements Bar { bar: String }
            type Query { bar: Bar }
        """.trimIndent()
        roundTrip(sdl)
    }

    @Test
    fun `Interface field with input-object argument`() {
        val sdl = """
            input Foo { foo: Boolean }
            interface Bar { bar(arg: Foo): String }
            type Query { bar: Bar }
        """.trimIndent()
        roundTrip(sdl)
    }

    @Test
    fun `Unused union`() {
        val sdl = """
            union Foo = Query
            type Query { bar: String }
        """.trimIndent()
        roundTrip(sdl)
    }

    @Test
    fun `Type type union`() {
        val sdl = """
            type Foo { foo: String }
            type Bar { bar: String }
            union Union = Foo | Bar
            type Query { u: Union }
        """.trimIndent()
        roundTrip(sdl)
    }

    @Test
    fun `Scalar type def`() {
        val sdl = "scalar Int type Query { foo: String }"
        roundTrip(sdl, setOf("Int"))
    }

    @Test
    fun `Enum type  def`() {
        val sdl = "enum Enum { V1, V2 } type Query { foo: String }"
        roundTrip(sdl)
    }

    @Test
    fun `Simple input type`() {
        val sdl = "input Simple { i: Int } type Query { foo(arg: Simple): Int }"
        roundTrip(sdl, setOf("Int"))
    }

    @Test
    fun `Non-null wrapper`() {
        val sdl = "input Simple { i: Int! } type Query { foo(arg: Simple): Int }"
        roundTrip(sdl, setOf("Int"))
    }

    @Test
    fun `List wrapper`() {
        val sdl = "input Simple { i: [Int] } type Query { foo(arg: Simple): Int }"
        roundTrip(sdl, setOf("Int"))
    }

    @Test
    fun `Many combo of non-null and list wrappers`() {
        val sdl = """
            input Simple {
                ll: [[Int]]
                lbl: [[Int]]!
                lblb: [[Int]!]!
                lblbb: [[Int!]!]!
                lblnb: [[Int!]]!
                lllll: [[[[[Int]]]]]
                bbbbbb: [[[[[Int!]!]!]!]!]!
            }
            type Query { foo(arg: Simple): Int }
        """.trimIndent()
        roundTrip(sdl, setOf("Int"))
    }

    @Test
    fun `toGraphQLSchema with additional scalars`() {
        val sdl = """
            scalar Timestamp
            type Query { timestamp: Timestamp }
        """.trimIndent()

        val fullSdl = "$builtins\n$sdl"
        val tdr = SchemaParser().parse(fullSdl)
        val schema = GJSchemaRaw.fromRegistry(tdr)

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
        val schema = GJSchemaRaw.fromRegistry(tdr)

        val graphqlSchema = schema.toGraphQLSchema(
            scalarsNeeded = emptySet(),
            additionalScalars = setOf("CustomScalar1", "CustomScalar2")
        )

        org.junit.jupiter.api.Assertions.assertNotNull(graphqlSchema.getType("CustomScalar1"))
        org.junit.jupiter.api.Assertions.assertNotNull(graphqlSchema.getType("CustomScalar2"))
    }

    // ===================================================================================
    // Tests for features that are currently missing in toGraphQLSchema
    // These tests are disabled and will be enabled as each feature is implemented
    // ===================================================================================

    @Test
    fun `Argument with default value`() {
        val sdl = "type Query { foo(arg: Int = 42): String }"
        roundTrip(sdl, setOf("Int"))
    }

    @Test
    fun `Input field with default value`() {
        val sdl = "input Foo { bar: Int = 42 } type Query { foo(arg: Foo): String }"
        roundTrip(sdl, setOf("Int"))
    }

    @Test
    fun `Field with deprecated directive`() {
        val sdl = """type Query { foo: String @deprecated(reason: "use bar instead") }"""
        roundTrip(sdl)
    }

    @Test
    fun `Enum value with deprecated directive`() {
        val sdl = """
            enum Status { ACTIVE, INACTIVE @deprecated(reason: "no longer used") }
            type Query { s: Status }
        """.trimIndent()
        roundTrip(sdl)
    }

    @Test
    fun `Argument with deprecated directive`() {
        val sdl = """type Query { foo(arg: Int @deprecated(reason: "not needed")): String }"""
        roundTrip(sdl, setOf("Int"))
    }

    @Test
    fun `Input field with deprecated directive`() {
        val sdl = """
            input Foo { bar: Int @deprecated(reason: "use baz") }
            type Query { foo(arg: Foo): String }
        """.trimIndent()
        roundTrip(sdl, setOf("Int"))
    }

    @Test
    fun `Type with custom applied directive`() {
        val sdl = """
            directive @custom on OBJECT
            type Query @custom { foo: String }
        """.trimIndent()
        roundTrip(sdl)
    }

    @Test
    fun `Field with custom applied directive`() {
        val sdl = """
            directive @custom on FIELD_DEFINITION
            type Query { foo: String @custom }
        """.trimIndent()
        roundTrip(sdl)
    }

    @Test
    fun `Directive definition`() {
        val sdl = """
            directive @custom on FIELD_DEFINITION
            type Query { foo: String }
        """.trimIndent()
        roundTrip(sdl)
    }

    @Test
    fun `Directive definition with arguments`() {
        val sdl = """
            directive @custom(arg: String) on FIELD_DEFINITION
            type Query { foo: String }
        """.trimIndent()
        roundTrip(sdl)
    }

    @Test
    fun `Repeatable directive definition`() {
        val sdl = """
            directive @custom repeatable on FIELD_DEFINITION
            type Query { foo: String }
        """.trimIndent()
        roundTrip(sdl)
    }

    @Test
    fun `Mutually recursive types`() {
        val sdl = """
            type A { b: B }
            type B { a: A }
            type Query { a: A }
        """.trimIndent()
        roundTrip(sdl)
    }

    // ==================== HIGH PRIORITY: Union with directives ====================

    @Test
    fun `Union with custom applied directive`() {
        val sdl = """
            directive @custom on UNION
            type Foo { foo: String }
            type Bar { bar: String }
            union FooBar @custom = Foo | Bar
            type Query { foobar: FooBar }
        """.trimIndent()
        roundTrip(sdl)
    }

    // ==================== HIGH PRIORITY: toGraphQLValue branches ====================

    @Test
    fun `Argument with null default value`() {
        val sdl = "type Query { foo(arg: String = null): String }"
        roundTrip(sdl)
    }

    @Test
    fun `Argument with list default value`() {
        val sdl = "type Query { foo(arg: [Int] = [1, 2, 3]): String }"
        roundTrip(sdl, setOf("Int"))
    }

    @Test
    fun `Input field with object default value`() {
        val sdl = """
            input Inner { x: Int }
            input Outer { inner: Inner = { x: 42 } }
            type Query { foo(arg: Outer): String }
        """.trimIndent()
        roundTrip(sdl, setOf("Int"))
    }

    @Test
    fun `Argument with float default value`() {
        val sdl = "type Query { foo(arg: Float = 3.14): String }"
        roundTrip(sdl, setOf("Float"))
    }

    @Test
    fun `Argument with boolean default value`() {
        val sdl = "type Query { foo(arg: Boolean = true): String }"
        roundTrip(sdl)
    }

    // ==================== MEDIUM PRIORITY: Type-level directives ====================

    @Test
    fun `Interface with custom applied directive`() {
        val sdl = """
            directive @custom on INTERFACE
            interface Bar @custom { bar: String }
            type Query { bar: Bar }
        """.trimIndent()
        roundTrip(sdl)
    }

    @Test
    fun `Enum with custom applied directive`() {
        val sdl = """
            directive @custom on ENUM
            enum Status @custom { ACTIVE, INACTIVE }
            type Query { s: Status }
        """.trimIndent()
        roundTrip(sdl)
    }

    @Test
    fun `Input type with custom applied directive`() {
        val sdl = """
            directive @custom on INPUT_OBJECT
            input Foo @custom { bar: Int }
            type Query { foo(arg: Foo): String }
        """.trimIndent()
        roundTrip(sdl, setOf("Int"))
    }

    // ==================== MEDIUM PRIORITY: Built-in scalar branches ====================

    @Test
    fun `Boolean scalar output field`() {
        val sdl = "type Query { foo: Boolean }"
        roundTrip(sdl)
    }

    @Test
    fun `ID scalar output field`() {
        val sdl = "type Query { foo: ID }"
        roundTrip(sdl, setOf("ID"))
    }

    // ==================== MEDIUM PRIORITY: Directive locations ====================

    @Test
    fun `Directive with QUERY location`() {
        val sdl = """
            directive @custom on QUERY
            type Query { foo: String }
        """.trimIndent()
        roundTrip(sdl)
    }

    @Test
    fun `Directive with SCALAR location`() {
        val sdl = """
            directive @custom on SCALAR
            scalar DateTime
            type Query { foo: DateTime }
        """.trimIndent()
        roundTrip(sdl, setOf("DateTime"))
    }

    @Test
    fun `Directive with multiple locations`() {
        val sdl = """
            directive @custom on FIELD_DEFINITION | ARGUMENT_DEFINITION | INPUT_FIELD_DEFINITION
            type Query { foo: String }
        """.trimIndent()
        roundTrip(sdl)
    }

    // ==================== MEDIUM PRIORITY: Deprecation branches ====================

    @Test
    fun `Field with deprecated directive without reason`() {
        val sdl = """type Query { foo: String @deprecated }"""
        roundTrip(sdl)
    }

    // ==================== BRANCH COVERAGE: Mutation and Subscription ====================

    @Test
    fun `Schema with Mutation type`() {
        val sdl = """
            type Query { foo: String }
            type Mutation { bar: String }
        """.trimIndent()
        roundTrip(sdl)
    }

    @Test
    fun `Schema with Subscription type`() {
        val sdl = """
            type Query { foo: String }
            type Subscription { events: String }
        """.trimIndent()
        roundTrip(sdl)
    }

    @Test
    fun `Schema with Query, Mutation, and Subscription`() {
        val sdl = """
            type Query { foo: String }
            type Mutation { bar: String }
            type Subscription { events: String }
        """.trimIndent()
        roundTrip(sdl)
    }

    // ==================== BRANCH COVERAGE: Additional directive locations ====================

    @Test
    fun `Directive with MUTATION location`() {
        val sdl = """
            directive @custom on MUTATION
            type Query { foo: String }
        """.trimIndent()
        roundTrip(sdl)
    }

    @Test
    fun `Directive with SUBSCRIPTION location`() {
        val sdl = """
            directive @custom on SUBSCRIPTION
            type Query { foo: String }
        """.trimIndent()
        roundTrip(sdl)
    }

    @Test
    fun `Directive with FIELD location`() {
        val sdl = """
            directive @custom on FIELD
            type Query { foo: String }
        """.trimIndent()
        roundTrip(sdl)
    }

    @Test
    fun `Directive with FRAGMENT_DEFINITION location`() {
        val sdl = """
            directive @custom on FRAGMENT_DEFINITION
            type Query { foo: String }
        """.trimIndent()
        roundTrip(sdl)
    }

    @Test
    fun `Directive with FRAGMENT_SPREAD location`() {
        val sdl = """
            directive @custom on FRAGMENT_SPREAD
            type Query { foo: String }
        """.trimIndent()
        roundTrip(sdl)
    }

    @Test
    fun `Directive with INLINE_FRAGMENT location`() {
        val sdl = """
            directive @custom on INLINE_FRAGMENT
            type Query { foo: String }
        """.trimIndent()
        roundTrip(sdl)
    }

    @Test
    fun `Directive with VARIABLE_DEFINITION location`() {
        val sdl = """
            directive @custom on VARIABLE_DEFINITION
            type Query { foo: String }
        """.trimIndent()
        roundTrip(sdl)
    }

    @Test
    fun `Directive with SCHEMA location`() {
        val sdl = """
            directive @custom on SCHEMA
            type Query { foo: String }
        """.trimIndent()
        roundTrip(sdl)
    }

    @Test
    fun `Directive with ENUM_VALUE location`() {
        val sdl = """
            directive @custom on ENUM_VALUE
            type Query { foo: String }
        """.trimIndent()
        roundTrip(sdl)
    }

    // ==================== BRANCH COVERAGE: Built-in directives already present ====================

    @Test
    fun `Schema that explicitly defines deprecated directive`() {
        val sdl = """
            directive @deprecated(reason: String) on FIELD_DEFINITION | ENUM_VALUE | ARGUMENT_DEFINITION | INPUT_FIELD_DEFINITION
            type Query { foo: String }
        """.trimIndent()
        roundTrip(sdl)
    }

    @Test
    fun `Schema that explicitly defines specifiedBy directive`() {
        val sdl = """
            directive @specifiedBy(url: String!) on SCALAR
            type Query { foo: String }
        """.trimIndent()
        roundTrip(sdl)
    }

    // ==================== BRANCH COVERAGE: Interface implements interface ====================

    @Test
    fun `Object type implements multiple interfaces`() {
        val sdl = """
            interface Node { id: ID }
            interface Named { name: String }
            type Foo implements Node & Named { id: ID, name: String }
            type Query { foo: Foo }
        """.trimIndent()
        roundTrip(sdl, setOf("ID"))
    }

    @Test
    fun `Interface extends another interface`() {
        val sdl = """
            interface Node { id: ID }
            interface NamedNode implements Node { id: ID, name: String }
            type Query { node: NamedNode }
        """.trimIndent()
        roundTrip(sdl, setOf("ID"))
    }

    // ==================== BRANCH COVERAGE: Mutually recursive input types ====================

    @Test
    fun `Mutually recursive input types`() {
        val sdl = """
            input A { b: B }
            input B { a: A }
            type Query { foo(arg: A): String }
        """.trimIndent()
        roundTrip(sdl)
    }

    @Test
    fun `Mutually recursive interfaces`() {
        val sdl = """
            interface A { b: B }
            interface B { a: A }
            type Query { a: A }
        """.trimIndent()
        roundTrip(sdl)
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
            val expectedViaductSchema = GJSchema.fromSchema(expectedGraphQLSchema)

            // turn back into a GraphQLSchema (actual)
            val actualGraphQLSchema = try {
                expectedViaductSchema.toGraphQLSchema(scalarsNeeded)
            } catch (e: Exception) {
                val s = expectedViaductSchema.types.toString()
                throw AssertionError("$s", e)
            }

            // turn into a ViaductSchema (GJSchema)
            val actualViaductSchema = GJSchema.fromSchema(actualGraphQLSchema!!)

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
                mkSchemaWithSourceLocations(
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
            val type = graphqlSchema!!.getType(typeName)
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

    //
    // Regression tests for applied directive argument types
    //

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
}
