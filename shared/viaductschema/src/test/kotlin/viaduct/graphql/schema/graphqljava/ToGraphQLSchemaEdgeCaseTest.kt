package viaduct.graphql.schema.graphqljava

import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLUnionType
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.test.BUILTIN_SCALARS
import viaduct.graphql.schema.test.SchemaDiff
import viaduct.graphql.schema.test.mkSchemaWithSourceLocations

/**
 * Tests for edge cases in ToGraphQLSchema conversion.
 *
 * Basic type conversions, nullability, custom root types, and recursion are covered by GJRoundTripTest.
 * These tests focus on:
 * - Type caching verification (instance identity)
 * - Additional scalars parameter behavior
 * - Source location preservation
 * - Deep recursion patterns
 */
class ToGraphQLSchemaEdgeCaseTest {
    // ==================== TYPE CACHING ====================

    @Test
    fun `same type referenced multiple times uses cached instance`() {
        val sdl = """
            type Address { street: String }
            type User { home: Address, work: Address }
            type Company { hq: Address }
            type Query { user: User, company: Company }
        """.trimIndent()
        val schema = parseAndConvert(sdl)

        val userType = schema.getType("User") as GraphQLObjectType
        val companyType = schema.getType("Company") as GraphQLObjectType

        val homeAddress = userType.getFieldDefinition("home").type
        val workAddress = userType.getFieldDefinition("work").type
        val hqAddress = companyType.getFieldDefinition("hq").type

        // They should all reference the same type
        homeAddress shouldBe workAddress
        workAddress shouldBe hqAddress
    }

    // ==================== ADDITIONAL SCALARS ====================

    @Test
    fun `additionalScalars adds scalars not in schema`() {
        val sdl = """type Query { foo: String }"""
        val tdr = SchemaParser().parse(sdl)
        val originalSchema = SchemaGenerator().makeExecutableSchema(tdr, RuntimeWiring.MOCKED_WIRING)
        val viaductSchema = GJSchema.fromSchema(originalSchema)

        val convertedSchema = viaductSchema.toGraphQLSchema(
            scalarsNeeded = emptySet(),
            additionalScalars = setOf("CustomScalar1", "CustomScalar2")
        )

        convertedSchema.getType("CustomScalar1").shouldNotBeNull()
        convertedSchema.getType("CustomScalar2").shouldNotBeNull()
    }

    // ==================== SOURCE LOCATION PRESERVATION ====================

    @Test
    fun `object type preserves source location`() {
        val schema = mkSchemaWithSourceLocations(
            listOf("type Query { foo: String }" to "query.graphqls"),
            sdlWithNoLocation = BUILTIN_SCALARS
        )
        val converted = schema.toGraphQLSchema()
        val queryType = converted.queryType as GraphQLObjectType
        queryType.definition?.sourceLocation?.sourceName shouldBe "query.graphqls"
    }

    @Test
    fun `enum type preserves source location`() {
        val schema = mkSchemaWithSourceLocations(
            listOf(
                "enum Status { ACTIVE }" to "enums.graphqls",
                "type Query { s: Status }" to "query.graphqls"
            ),
            sdlWithNoLocation = BUILTIN_SCALARS
        )
        val converted = schema.toGraphQLSchema()
        val enumType = converted.getType("Status") as GraphQLEnumType
        enumType.definition?.sourceLocation?.sourceName shouldBe "enums.graphqls"
    }

    @Test
    fun `input type preserves source location`() {
        val schema = mkSchemaWithSourceLocations(
            listOf(
                "input Filter { name: String }" to "inputs.graphqls",
                "type Query { search(f: Filter): String }" to "query.graphqls"
            ),
            sdlWithNoLocation = BUILTIN_SCALARS
        )
        val converted = schema.toGraphQLSchema()
        val inputType = converted.getType("Filter") as GraphQLInputObjectType
        inputType.definition?.sourceLocation?.sourceName shouldBe "inputs.graphqls"
    }

    @Test
    fun `interface type preserves source location`() {
        val schema = mkSchemaWithSourceLocations(
            listOf(
                "interface Node { id: String }" to "interfaces.graphqls",
                "type Query { n: Node }" to "query.graphqls"
            ),
            sdlWithNoLocation = BUILTIN_SCALARS
        )
        val converted = schema.toGraphQLSchema()
        val interfaceType = converted.getType("Node") as GraphQLInterfaceType
        interfaceType.definition?.sourceLocation?.sourceName shouldBe "interfaces.graphqls"
    }

    @Test
    fun `union type preserves source location`() {
        val schema = mkSchemaWithSourceLocations(
            listOf(
                "type A { a: String }" to "types.graphqls",
                "type B { b: String }" to "types.graphqls",
                "union AB = A | B" to "unions.graphqls",
                "type Query { ab: AB }" to "query.graphqls"
            ),
            sdlWithNoLocation = BUILTIN_SCALARS
        )
        val converted = schema.toGraphQLSchema()
        val unionType = converted.getType("AB") as GraphQLUnionType
        unionType.definition?.sourceLocation?.sourceName shouldBe "unions.graphqls"
    }

    @Test
    fun `custom scalar preserves source location`() {
        val schema = mkSchemaWithSourceLocations(
            listOf(
                "scalar DateTime" to "scalars.graphqls",
                "type Query { ts: DateTime }" to "query.graphqls"
            ),
            sdlWithNoLocation = BUILTIN_SCALARS
        )
        val converted = schema.toGraphQLSchema(setOf("DateTime"))
        val scalarType = converted.getType("DateTime") as GraphQLScalarType
        scalarType.definition?.sourceLocation?.sourceName shouldBe "scalars.graphqls"
    }

    // ==================== DEEP RECURSION ====================

    @Test
    fun `deeply nested self-referential type`() {
        val sdl = """
            type Node {
                value: String
                parent: Node
                children: [Node]
                siblings: [Node]
            }
            type Query { root: Node }
        """.trimIndent()
        verifyRoundTrip(sdl)
    }

    @Test
    fun `recursive input types with defaults`() {
        val sdl = """
            input TreeNode {
                value: String = "default"
                children: [TreeNode] = []
            }
            type Query { create(node: TreeNode): String }
        """.trimIndent()
        verifyRoundTrip(sdl)
    }

    companion object {
        private fun verifyRoundTrip(
            sdl: String,
            scalarsNeeded: Set<String> = emptySet(),
        ) {
            val tdr = SchemaParser().parse(sdl)
            val expectedGraphQLSchema = SchemaGenerator().makeExecutableSchema(tdr, RuntimeWiring.MOCKED_WIRING)
            val expectedViaductSchema = GJSchema.fromSchema(expectedGraphQLSchema)

            val actualGraphQLSchema = expectedViaductSchema.toGraphQLSchema(scalarsNeeded)
            val actualViaductSchema = GJSchema.fromSchema(actualGraphQLSchema)

            val schemaDiffResult = SchemaDiff(expectedViaductSchema, actualViaductSchema).diff()
            schemaDiffResult.assertEmpty()

            val extraDiffResult = GraphQLSchemaExtraDiff(expectedGraphQLSchema, actualGraphQLSchema).diff()
            extraDiffResult.assertEmpty()
        }

        private fun parseAndConvert(
            sdl: String,
            scalarsNeeded: Set<String> = emptySet(),
        ): graphql.schema.GraphQLSchema {
            val tdr = SchemaParser().parse(sdl)
            val originalSchema = SchemaGenerator().makeExecutableSchema(tdr, RuntimeWiring.MOCKED_WIRING)
            val viaductSchema = GJSchema.fromSchema(originalSchema)
            return viaductSchema.toGraphQLSchema(scalarsNeeded)
        }
    }
}
