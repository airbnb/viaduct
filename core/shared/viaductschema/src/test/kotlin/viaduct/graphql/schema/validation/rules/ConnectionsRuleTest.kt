package viaduct.graphql.schema.validation.rules

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.graphql.schema.validation.SchemaValidator
import viaduct.graphql.schema.validation.ValidationErrorCodes
import viaduct.graphql.schema.validation.ValidationRule

private val PREAMBLE = """
    directive @connection on OBJECT
    directive @edge on OBJECT
""".trimIndent()

private fun validateWith(
    vararg rules: ValidationRule,
    sdl: String
) = SchemaValidator(listOf(rules.toList()))
    .validate(ViaductSchema.fromTypeDefinitionRegistry("$PREAMBLE\n$sdl"))

class ConnectionsRuleTest {
    @Nested
    inner class ConnectionTypeStructureRuleTest {
        private fun validate(sdl: String) = validateWith(ConnectionTypeStructureRule(), sdl = sdl)

        @Test
        fun `valid - connection type with edges and non-null pageInfo`() {
            val errors = validate(
                """
                type Query { listings: ListingConnection }
                type ListingConnection @connection {
                    edges: [ListingEdge]
                    pageInfo: PageInfo!
                }
                type ListingEdge @edge { node: String }
                type PageInfo { hasNextPage: Boolean! }
                """.trimIndent()
            )

            errors.shouldBeEmpty()
        }

        @Test
        fun `valid - non-connection type is ignored`() {
            val errors = validate(
                """
                type Query { foo: Foo }
                type Foo { bar: String }
                """.trimIndent()
            )

            errors.shouldBeEmpty()
        }

        @Test
        fun `invalid - missing edges field`() {
            val errors = validate(
                """
                type Query { listings: ListingConnection }
                type ListingConnection @connection {
                    pageInfo: PageInfo!
                }
                type PageInfo { hasNextPage: Boolean! }
                """.trimIndent()
            )

            errors shouldHaveSize 1
            errors[0].code shouldBe ValidationErrorCodes.CONNECTION_MISSING_EDGES_FIELD
            errors[0].message shouldContain "ListingConnection"
            errors[0].message shouldContain "edges"
        }

        @Test
        fun `invalid - edges base type does not have @edge directive`() {
            val errors = validate(
                """
                type Query { listings: ListingConnection }
                type ListingConnection @connection {
                    edges: [ListingEdge]
                    pageInfo: PageInfo!
                }
                type ListingEdge { node: String }
                type PageInfo { hasNextPage: Boolean! }
                """.trimIndent()
            )

            errors shouldHaveSize 1
            errors[0].code shouldBe ValidationErrorCodes.CONNECTION_EDGES_TYPE_NOT_EDGE
            errors[0].message shouldContain "ListingConnection"
            errors[0].message shouldContain "ListingEdge"
            errors[0].message shouldContain "@edge"
        }

        @Test
        fun `invalid - missing pageInfo field`() {
            val errors = validate(
                """
                type Query { listings: ListingConnection }
                type ListingConnection @connection {
                    edges: [ListingEdge]
                }
                type ListingEdge @edge { node: String }
                """.trimIndent()
            )

            errors shouldHaveSize 1
            errors[0].code shouldBe ValidationErrorCodes.CONNECTION_MISSING_PAGE_INFO_FIELD
            errors[0].message shouldContain "ListingConnection"
            errors[0].message shouldContain "pageInfo"
        }

        @Test
        fun `invalid - pageInfo field is nullable`() {
            val errors = validate(
                """
                type Query { listings: ListingConnection }
                type ListingConnection @connection {
                    edges: [ListingEdge]
                    pageInfo: PageInfo
                }
                type ListingEdge @edge { node: String }
                type PageInfo { hasNextPage: Boolean! }
                """.trimIndent()
            )

            errors shouldHaveSize 1
            errors[0].code shouldBe ValidationErrorCodes.CONNECTION_PAGE_INFO_MUST_BE_NON_NULL
            errors[0].message shouldContain "ListingConnection"
            errors[0].message shouldContain "pageInfo"
            errors[0].message shouldContain "non-null"
        }

        @Test
        fun `multiple violations are reported`() {
            val errors = validate(
                """
                type Query { listings: ListingConnection }
                type ListingConnection @connection {
                    pageInfo: PageInfo
                }
                type PageInfo { hasNextPage: Boolean! }
                """.trimIndent()
            )

            errors shouldHaveSize 2
            errors.map { it.code } shouldBe listOf(
                ValidationErrorCodes.CONNECTION_MISSING_EDGES_FIELD,
                ValidationErrorCodes.CONNECTION_PAGE_INFO_MUST_BE_NON_NULL
            )
        }
    }

    @Nested
    inner class ConnectionEdgeStructureRuleTest {
        private fun validate(sdl: String) = validateWith(ConnectionEdgeStructureRule(), sdl = sdl)

        @Test
        fun `valid - edge type has node field`() {
            val errors = validate(
                """
                type Query { foo: String }
                type ListingEdge @edge { node: String }
                """.trimIndent()
            )

            errors.shouldBeEmpty()
        }

        @Test
        fun `valid - non-edge type without node field is ignored`() {
            val errors = validate(
                """
                type Query { foo: Foo }
                type Foo { bar: String }
                """.trimIndent()
            )

            errors.shouldBeEmpty()
        }

        @Test
        fun `invalid - edge type missing node field`() {
            val errors = validate(
                """
                type Query { foo: String }
                type ListingEdge @edge { cursor: String }
                """.trimIndent()
            )

            errors shouldHaveSize 1
            errors[0].code shouldBe ValidationErrorCodes.EDGE_MISSING_NODE_FIELD
            errors[0].message shouldContain "ListingEdge"
            errors[0].message shouldContain "node"
        }

        @Test
        fun `multiple edge types each independently validated`() {
            val errors = validate(
                """
                type Query { foo: String }
                type EdgeA @edge { cursor: String }
                type EdgeB @edge { node: String }
                type EdgeC @edge { cursor: String }
                """.trimIndent()
            )

            errors shouldHaveSize 2
            errors.map { it.code } shouldBe listOf(
                ValidationErrorCodes.EDGE_MISSING_NODE_FIELD,
                ValidationErrorCodes.EDGE_MISSING_NODE_FIELD
            )
            errors.map { it.message }.any { it.contains("EdgeA") } shouldBe true
            errors.map { it.message }.any { it.contains("EdgeC") } shouldBe true
        }
    }

    @Nested
    inner class ConnectionPageInfoRuleTest {
        private fun validate(sdl: String) = validateWith(ConnectionPageInfoRule(), sdl = sdl)

        @Test
        fun `valid - pageInfo conforms to Relay spec`() {
            val errors = validate(
                """
                type Query { listings: ListingConnection }
                type ListingConnection @connection {
                    edges: [ListingEdge]
                    pageInfo: PageInfo!
                }
                type ListingEdge @edge { node: String }
                type PageInfo {
                    hasNextPage: Boolean!
                    hasPreviousPage: Boolean!
                    startCursor: String
                    endCursor: String
                }
                """.trimIndent()
            )

            errors.shouldBeEmpty()
        }

        @Test
        fun `valid - connection with missing pageInfo is skipped (handled by ConnectionTypeStructureRule)`() {
            val errors = validate(
                """
                type Query { listings: ListingConnection }
                type ListingConnection @connection {
                    edges: [ListingEdge]
                }
                type ListingEdge @edge { node: String }
                """.trimIndent()
            )

            errors.shouldBeEmpty()
        }

        @Test
        fun `invalid - hasNextPage missing`() {
            val errors = validate(
                """
                type Query { listings: ListingConnection }
                type ListingConnection @connection {
                    edges: [ListingEdge]
                    pageInfo: PageInfo!
                }
                type ListingEdge @edge { node: String }
                type PageInfo {
                    hasPreviousPage: Boolean!
                    startCursor: String
                    endCursor: String
                }
                """.trimIndent()
            )

            errors shouldHaveSize 1
            errors[0].code shouldBe ValidationErrorCodes.PAGE_INFO_MISSING_REQUIRED_FIELD
            errors[0].message shouldContain "hasNextPage"
            errors[0].message shouldContain "PageInfo"
        }

        @Test
        fun `invalid - hasPreviousPage missing`() {
            val errors = validate(
                """
                type Query { listings: ListingConnection }
                type ListingConnection @connection {
                    edges: [ListingEdge]
                    pageInfo: PageInfo!
                }
                type ListingEdge @edge { node: String }
                type PageInfo {
                    hasNextPage: Boolean!
                    startCursor: String
                    endCursor: String
                }
                """.trimIndent()
            )

            errors shouldHaveSize 1
            errors[0].code shouldBe ValidationErrorCodes.PAGE_INFO_MISSING_REQUIRED_FIELD
            errors[0].message shouldContain "hasPreviousPage"
            errors[0].message shouldContain "PageInfo"
        }

        @Test
        fun `invalid - hasNextPage is nullable`() {
            val errors = validate(
                """
                type Query { listings: ListingConnection }
                type ListingConnection @connection {
                    edges: [ListingEdge]
                    pageInfo: PageInfo!
                }
                type ListingEdge @edge { node: String }
                type PageInfo {
                    hasNextPage: Boolean
                    hasPreviousPage: Boolean!
                    startCursor: String
                    endCursor: String
                }
                """.trimIndent()
            )

            errors shouldHaveSize 1
            errors[0].code shouldBe ValidationErrorCodes.PAGE_INFO_BOOLEAN_FIELD_MUST_BE_NON_NULL_BOOLEAN
            errors[0].message shouldContain "hasNextPage"
            errors[0].message shouldContain "Boolean!"
        }

        @Test
        fun `invalid - hasPreviousPage is not Boolean`() {
            val errors = validate(
                """
                type Query { listings: ListingConnection }
                type ListingConnection @connection {
                    edges: [ListingEdge]
                    pageInfo: PageInfo!
                }
                type ListingEdge @edge { node: String }
                type PageInfo {
                    hasNextPage: Boolean!
                    hasPreviousPage: String!
                    startCursor: String
                    endCursor: String
                }
                """.trimIndent()
            )

            errors shouldHaveSize 1
            errors[0].code shouldBe ValidationErrorCodes.PAGE_INFO_BOOLEAN_FIELD_MUST_BE_NON_NULL_BOOLEAN
            errors[0].message shouldContain "hasPreviousPage"
            errors[0].message shouldContain "Boolean!"
        }

        @Test
        fun `invalid - startCursor is non-null`() {
            val errors = validate(
                """
                type Query { listings: ListingConnection }
                type ListingConnection @connection {
                    edges: [ListingEdge]
                    pageInfo: PageInfo!
                }
                type ListingEdge @edge { node: String }
                type PageInfo {
                    hasNextPage: Boolean!
                    hasPreviousPage: Boolean!
                    startCursor: String!
                    endCursor: String
                }
                """.trimIndent()
            )

            errors shouldHaveSize 1
            errors[0].code shouldBe ValidationErrorCodes.PAGE_INFO_CURSOR_MUST_BE_NULLABLE
            errors[0].message shouldContain "startCursor"
            errors[0].message shouldContain "nullable"
        }

        @Test
        fun `invalid - endCursor is non-null`() {
            val errors = validate(
                """
                type Query { listings: ListingConnection }
                type ListingConnection @connection {
                    edges: [ListingEdge]
                    pageInfo: PageInfo!
                }
                type ListingEdge @edge { node: String }
                type PageInfo {
                    hasNextPage: Boolean!
                    hasPreviousPage: Boolean!
                    startCursor: String
                    endCursor: String!
                }
                """.trimIndent()
            )

            errors shouldHaveSize 1
            errors[0].code shouldBe ValidationErrorCodes.PAGE_INFO_CURSOR_MUST_BE_NULLABLE
            errors[0].message shouldContain "endCursor"
            errors[0].message shouldContain "nullable"
        }

        @Test
        fun `invalid - startCursor and endCursor missing`() {
            val errors = validate(
                """
                type Query { listings: ListingConnection }
                type ListingConnection @connection {
                    edges: [ListingEdge]
                    pageInfo: PageInfo!
                }
                type ListingEdge @edge { node: String }
                type PageInfo {
                    hasNextPage: Boolean!
                    hasPreviousPage: Boolean!
                }
                """.trimIndent()
            )

            errors shouldHaveSize 2
            errors.map { it.code } shouldBe listOf(
                ValidationErrorCodes.PAGE_INFO_MISSING_REQUIRED_FIELD,
                ValidationErrorCodes.PAGE_INFO_MISSING_REQUIRED_FIELD
            )
            errors.map { it.message }.any { it.contains("startCursor") } shouldBe true
            errors.map { it.message }.any { it.contains("endCursor") } shouldBe true
        }
    }

    @Nested
    inner class ConnectionArgumentsNullabilityRuleTest {
        private fun validate(sdl: String) = validateWith(ConnectionArgumentsNullabilityRule(), sdl = sdl)

        @Test
        fun `valid - nullable pagination args on connection-returning field`() {
            val errors = validate(
                """
                type Query {
                    listings(first: Int, after: String, last: Int, before: String): ListingConnection
                }
                type ListingConnection @connection {
                    edges: [ListingEdge]
                    pageInfo: PageInfo!
                }
                type ListingEdge @edge { node: String }
                type PageInfo { hasNextPage: Boolean! }
                """.trimIndent()
            )

            errors.shouldBeEmpty()
        }

        @Test
        fun `valid - non-nullable args on non-connection-returning field are ignored`() {
            val errors = validate(
                """
                type Query {
                    listing(first: Int!, after: String!): String
                }
                """.trimIndent()
            )

            errors.shouldBeEmpty()
        }

        @Test
        fun `valid - non-pagination args on connection-returning field are ignored`() {
            val errors = validate(
                """
                type Query {
                    listings(filter: String!, sort: String!): ListingConnection
                }
                type ListingConnection @connection {
                    edges: [ListingEdge]
                    pageInfo: PageInfo!
                }
                type ListingEdge @edge { node: String }
                type PageInfo { hasNextPage: Boolean! }
                """.trimIndent()
            )

            errors.shouldBeEmpty()
        }

        @Test
        fun `invalid - first is non-null on connection-returning field`() {
            val errors = validate(
                """
                type Query {
                    listings(first: Int!): ListingConnection
                }
                type ListingConnection @connection {
                    edges: [ListingEdge]
                    pageInfo: PageInfo!
                }
                type ListingEdge @edge { node: String }
                type PageInfo { hasNextPage: Boolean! }
                """.trimIndent()
            )

            errors shouldHaveSize 1
            errors[0].code shouldBe ValidationErrorCodes.CONNECTION_ARG_MUST_BE_NULLABLE
            errors[0].message shouldContain "first"
            errors[0].message shouldContain "Query.listings"
            errors[0].message shouldContain "nullable"
        }

        @Test
        fun `invalid - after is non-null on connection-returning field`() {
            val errors = validate(
                """
                type Query {
                    listings(after: String!): ListingConnection
                }
                type ListingConnection @connection {
                    edges: [ListingEdge]
                    pageInfo: PageInfo!
                }
                type ListingEdge @edge { node: String }
                type PageInfo { hasNextPage: Boolean! }
                """.trimIndent()
            )

            errors shouldHaveSize 1
            errors[0].code shouldBe ValidationErrorCodes.CONNECTION_ARG_MUST_BE_NULLABLE
            errors[0].message shouldContain "after"
            errors[0].message shouldContain "Query.listings"
        }

        @Test
        fun `invalid - multiple non-null pagination args`() {
            val errors = validate(
                """
                type Query {
                    listings(first: Int!, after: String!, last: Int!, before: String!): ListingConnection
                }
                type ListingConnection @connection {
                    edges: [ListingEdge]
                    pageInfo: PageInfo!
                }
                type ListingEdge @edge { node: String }
                type PageInfo { hasNextPage: Boolean! }
                """.trimIndent()
            )

            errors shouldHaveSize 4
            errors.map { it.code } shouldBe listOf(
                ValidationErrorCodes.CONNECTION_ARG_MUST_BE_NULLABLE,
                ValidationErrorCodes.CONNECTION_ARG_MUST_BE_NULLABLE,
                ValidationErrorCodes.CONNECTION_ARG_MUST_BE_NULLABLE,
                ValidationErrorCodes.CONNECTION_ARG_MUST_BE_NULLABLE
            )
            errors.map { it.message }.any { it.contains("first") } shouldBe true
            errors.map { it.message }.any { it.contains("after") } shouldBe true
            errors.map { it.message }.any { it.contains("last") } shouldBe true
            errors.map { it.message }.any { it.contains("before") } shouldBe true
        }
    }
}
