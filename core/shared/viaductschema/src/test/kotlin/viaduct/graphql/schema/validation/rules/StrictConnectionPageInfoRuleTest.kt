package viaduct.graphql.schema.validation.rules

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.graphql.schema.validation.SchemaValidator
import viaduct.graphql.schema.validation.ValidationErrorCodes

class StrictConnectionPageInfoRuleTest {
    private val preamble = """
        directive @connection on OBJECT
        directive @edge on OBJECT
    """.trimIndent()

    private val pageInfoSdl = """
        type PageInfo {
            hasNextPage: Boolean!
            hasPreviousPage: Boolean!
            startCursor: String
            endCursor: String
        }
    """.trimIndent()

    private fun validate(sdl: String) =
        SchemaValidator(listOf(listOf(StrictConnectionPageInfoRule())))
            .validate(ViaductSchema.fromTypeDefinitionRegistry("$preamble\n$sdl"))

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
    fun `valid - standard pageInfo has no interfaces or unions`() {
        validatePageInfo(pageInfoSdl).shouldBeEmpty()
    }

    @Test
    fun `valid - pageInfo implements interface is allowed without this rule`() {
        val errors = SchemaValidator(listOf(listOf(ConnectionPageInfoRule())))
            .validate(
                ViaductSchema.fromTypeDefinitionRegistry(
                    """
                    $preamble
                    interface IPageInfo { hasNextPage: Boolean! }
                    type PageInfo implements IPageInfo {
                        hasNextPage: Boolean!
                        hasPreviousPage: Boolean!
                        startCursor: String
                        endCursor: String
                    }
                    type MyEdge @edge { node: String }
                    type MyConnection @connection {
                        edges: [MyEdge]
                        pageInfo: PageInfo!
                    }
                    type Query { items: MyConnection }
                    """.trimIndent()
                )
            )
        errors.shouldBeEmpty()
    }

    @Test
    fun `invalid - pageInfo implements an interface`() {
        val errors = validatePageInfo(
            """
            interface Cursor { startCursor: String }
            type PageInfo implements Cursor {
                hasNextPage: Boolean!
                hasPreviousPage: Boolean!
                startCursor: String
                endCursor: String
            }
            """.trimIndent()
        )
        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.PAGE_INFO_IMPLEMENTS_INTERFACE
        errors[0].message shouldContain "Cursor"
    }

    @Test
    fun `invalid - pageInfo is a member of a union`() {
        val errors = validatePageInfo(
            """
            $pageInfoSdl
            type OtherType { name: String }
            union MixedResult = PageInfo | OtherType
            """.trimIndent()
        )
        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.PAGE_INFO_IN_UNION
        errors[0].message shouldContain "MixedResult"
    }
}
