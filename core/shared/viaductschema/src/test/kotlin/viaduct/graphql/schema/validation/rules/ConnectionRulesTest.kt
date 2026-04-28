package viaduct.graphql.schema.validation.rules

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
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

class ConnectionRulesTest {
    private val preamble = """
        directive @connection on OBJECT
        directive @edge on OBJECT
    """.trimIndent()

    private fun validate(
        rules: List<ValidationRule>,
        sdl: String
    ) = SchemaValidator(listOf(rules))
        .validate(ViaductSchema.fromTypeDefinitionRegistry("$preamble\n$sdl"))

    private val pageInfoSdl = """
        type PageInfo {
            hasNextPage: Boolean!
            hasPreviousPage: Boolean!
            startCursor: String
            endCursor: String
        }
    """.trimIndent()

    @Nested
    inner class ConnectionTypeStructureRuleTest {
        private val rule = ConnectionTypeStructureRule()

        private fun validate(sdl: String) = validate(listOf(rule), sdl)

        @Test
        fun `valid - connection type has both edges and pageInfo`() {
            val errors = validate(
                """
                $pageInfoSdl
                type MyEdge @edge { node: String }
                type MyConnection @connection {
                    edges: [MyEdge]
                    pageInfo: PageInfo!
                }
                type Query { items: MyConnection }
                """.trimIndent()
            )
            errors.shouldBeEmpty()
        }

        @Test
        fun `invalid - connection type missing edges field`() {
            val errors = validate(
                """
                $pageInfoSdl
                type MyConnection @connection {
                    pageInfo: PageInfo!
                }
                type Query { items: MyConnection }
                """.trimIndent()
            )
            errors shouldHaveSize 1
            errors[0].code shouldBe ValidationErrorCodes.CONNECTION_MISSING_EDGES_FIELD
            errors[0].message shouldContain "MyConnection"
            errors[0].message shouldContain "edges"
        }

        @Test
        fun `invalid - connection type edges base type lacks @edge directive`() {
            val errors = validate(
                """
                $pageInfoSdl
                type PlainEdge { node: String }
                type MyConnection @connection {
                    edges: [PlainEdge]
                    pageInfo: PageInfo!
                }
                type Query { items: MyConnection }
                """.trimIndent()
            )
            errors shouldHaveSize 1
            errors[0].code shouldBe ValidationErrorCodes.CONNECTION_EDGES_TYPE_NOT_EDGE
            errors[0].message shouldContain "MyConnection"
            errors[0].message shouldContain "PlainEdge"
        }

        @Test
        fun `invalid - connection type missing pageInfo field`() {
            val errors = validate(
                """
                type MyEdge @edge { node: String }
                type MyConnection @connection {
                    edges: [MyEdge]
                }
                type Query { items: MyConnection }
                """.trimIndent()
            )
            errors shouldHaveSize 1
            errors[0].code shouldBe ValidationErrorCodes.CONNECTION_MISSING_PAGE_INFO_FIELD
            errors[0].message shouldContain "MyConnection"
            errors[0].message shouldContain "pageInfo"
        }

        @Test
        fun `invalid - connection type has nullable pageInfo`() {
            val errors = validate(
                """
                $pageInfoSdl
                type MyEdge @edge { node: String }
                type MyConnection @connection {
                    edges: [MyEdge]
                    pageInfo: PageInfo
                }
                type Query { items: MyConnection }
                """.trimIndent()
            )
            errors shouldHaveSize 1
            errors[0].code shouldBe ValidationErrorCodes.CONNECTION_PAGE_INFO_MUST_BE_NON_NULL
            errors[0].message shouldContain "MyConnection"
            errors[0].message shouldContain "non-null"
        }

        @Test
        fun `invalid - connection type missing both edges and pageInfo`() {
            val errors = validate(
                """
                type MyConnection @connection { name: String }
                type Query { items: MyConnection }
                """.trimIndent()
            )
            errors shouldHaveSize 2
            errors.map { it.code } shouldContainExactlyInAnyOrder listOf(
                ValidationErrorCodes.CONNECTION_MISSING_EDGES_FIELD,
                ValidationErrorCodes.CONNECTION_MISSING_PAGE_INFO_FIELD
            )
        }

        @Test
        fun `non-connection type is not validated`() {
            val errors = validate(
                """
                type NotAConnection { name: String }
                type Query { items: NotAConnection }
                """.trimIndent()
            )
            errors.shouldBeEmpty()
        }
    }

    @Nested
    inner class ConnectionEdgeStructureRuleTest {
        private val rule = ConnectionEdgeStructureRule()

        private fun validate(sdl: String) = validate(listOf(rule), sdl)

        @Test
        fun `valid - edge type has node field`() {
            val errors = validate(
                """
                type MyEdge @edge { node: String }
                type Query { placeholder: String }
                """.trimIndent()
            )
            errors.shouldBeEmpty()
        }

        @Test
        fun `invalid - edge type missing node field`() {
            val errors = validate(
                """
                type MyEdge @edge { cursor: String }
                type Query { placeholder: String }
                """.trimIndent()
            )
            errors shouldHaveSize 1
            errors[0].code shouldBe ValidationErrorCodes.EDGE_MISSING_NODE_FIELD
            errors[0].message shouldContain "MyEdge"
            errors[0].message shouldContain "node"
        }

        @Test
        fun `non-edge type is not validated`() {
            val errors = validate(
                """
                type NotAnEdge { cursor: String }
                type Query { placeholder: String }
                """.trimIndent()
            )
            errors.shouldBeEmpty()
        }

        @Test
        fun `multiple edge types missing node field each produce an error`() {
            val errors = validate(
                """
                type EdgeA @edge { cursor: String }
                type EdgeB @edge { cursor: String }
                type Query { placeholder: String }
                """.trimIndent()
            )
            errors shouldHaveSize 2
            errors.map { it.code } shouldContainExactlyInAnyOrder listOf(
                ValidationErrorCodes.EDGE_MISSING_NODE_FIELD,
                ValidationErrorCodes.EDGE_MISSING_NODE_FIELD
            )
            errors.any { it.message.contains("EdgeA") } shouldBe true
            errors.any { it.message.contains("EdgeB") } shouldBe true
        }
    }

    @Nested
    inner class ConnectionPageInfoRuleTest {
        private val rule = ConnectionPageInfoRule()

        private fun validate(sdl: String) = validate(listOf(rule), sdl)

        private fun validatePageInfo(pageInfoBlock: String) =
            validate(
                """
            $pageInfoBlock
            type MyEdge @edge { node: String }
            type MyConnection @connection {
                edges: [MyEdge]
                pageInfo: PageInfo!
            }
            type Query { items: MyConnection }
                """.trimIndent()
            )

        @Test
        fun `valid - pageInfo conforms to Relay spec`() {
            val errors = validatePageInfo(pageInfoSdl)
            errors.shouldBeEmpty()
        }

        @Test
        fun `invalid - pageInfo missing hasNextPage`() {
            val errors = validatePageInfo(
                """
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
        }

        @Test
        fun `invalid - pageInfo missing hasPreviousPage`() {
            val errors = validatePageInfo(
                """
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
        }

        @Test
        fun `invalid - hasNextPage is nullable`() {
            val errors = validatePageInfo(
                """
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
            val errors = validatePageInfo(
                """
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
        }

        @Test
        fun `invalid - startCursor is non-null`() {
            val errors = validatePageInfo(
                """
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
            val errors = validatePageInfo(
                """
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
        }

        @Test
        fun `invalid - pageInfo missing startCursor and endCursor`() {
            val errors = validatePageInfo(
                """
                type PageInfo {
                    hasNextPage: Boolean!
                    hasPreviousPage: Boolean!
                }
                """.trimIndent()
            )
            errors shouldHaveSize 2
            errors.map { it.code } shouldContainExactlyInAnyOrder listOf(
                ValidationErrorCodes.PAGE_INFO_MISSING_REQUIRED_FIELD,
                ValidationErrorCodes.PAGE_INFO_MISSING_REQUIRED_FIELD
            )
            errors.any { it.message.contains("startCursor") } shouldBe true
            errors.any { it.message.contains("endCursor") } shouldBe true
        }

        @Test
        fun `non-connection type does not trigger pageInfo validation`() {
            val errors = validate(
                """
                type NotAConnection { name: String }
                type Query { items: NotAConnection }
                """.trimIndent()
            )
            errors.shouldBeEmpty()
        }

        @Test
        fun `connection missing pageInfo field skips pageInfo content validation`() {
            // ConnectionTypeStructureRule reports the missing pageInfo; this rule should not
            // double-report or crash when the pageInfo field is absent.
            val errors = validate(
                """
                type MyEdge @edge { node: String }
                type MyConnection @connection { edges: [MyEdge] }
                type Query { items: MyConnection }
                """.trimIndent()
            )
            errors.shouldBeEmpty()
        }
    }

    @Nested
    inner class ConnectionArgumentsNullabilityRuleTest {
        private val rule = ConnectionArgumentsNullabilityRule()

        private fun validate(sdl: String) = validate(listOf(rule), sdl)

        private val connectionSdl = """
            $pageInfoSdl
            type MyEdge @edge { node: String }
            type MyConnection @connection {
                edges: [MyEdge]
                pageInfo: PageInfo!
            }
        """.trimIndent()

        @Test
        fun `valid - connection pagination args are nullable`() {
            val errors = validate(
                """
                $connectionSdl
                type Query {
                    items(first: Int, after: String, last: Int, before: String): MyConnection
                }
                """.trimIndent()
            )
            errors.shouldBeEmpty()
        }

        @Test
        fun `invalid - first arg is non-null on connection field`() {
            val errors = validate(
                """
                $connectionSdl
                type Query { items(first: Int!): MyConnection }
                """.trimIndent()
            )
            errors shouldHaveSize 1
            errors[0].code shouldBe ValidationErrorCodes.CONNECTION_ARG_MUST_BE_NULLABLE
            errors[0].message shouldContain "first"
            errors[0].message shouldContain "Query.items"
        }

        @Test
        fun `invalid - after arg is non-null on connection field`() {
            val errors = validate(
                """
                $connectionSdl
                type Query { items(after: String!): MyConnection }
                """.trimIndent()
            )
            errors shouldHaveSize 1
            errors[0].code shouldBe ValidationErrorCodes.CONNECTION_ARG_MUST_BE_NULLABLE
            errors[0].message shouldContain "after"
        }

        @Test
        fun `invalid - all four pagination args non-null produce four errors`() {
            val errors = validate(
                """
                $connectionSdl
                type Query {
                    items(first: Int!, after: String!, last: Int!, before: String!): MyConnection
                }
                """.trimIndent()
            )
            errors shouldHaveSize 4
            errors.map { it.code }.all { it == ValidationErrorCodes.CONNECTION_ARG_MUST_BE_NULLABLE } shouldBe true
        }

        @Test
        fun `valid - non-null pagination arg on non-connection field is allowed`() {
            val errors = validate(
                """
                type Query { search(first: Int!): String }
                """.trimIndent()
            )
            errors.shouldBeEmpty()
        }

        @Test
        fun `valid - non-pagination non-null arg on connection field is allowed`() {
            val errors = validate(
                """
                $connectionSdl
                type Query { items(filter: String!): MyConnection }
                """.trimIndent()
            )
            errors.shouldBeEmpty()
        }
    }
}
