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

class PageInfoLocationRuleTest {
    private fun rule() = PageInfoLocationRule(modulePathPrefix = "partition/")

    private fun validateInline(sdl: String) =
        SchemaValidator(listOf(listOf(rule())))
            .validate(ViaductSchema.fromTypeDefinitionRegistry(sdl))

    private fun validateFiles(vararg resourcePaths: String) =
        SchemaValidator(listOf(listOf(rule()))).validate(
            ViaductSchema.fromTypeDefinitionRegistry(
                resourcePaths.map { javaClass.getResource(it)!! }
            )
        )

    @Test
    fun `valid - PageInfo defined in schemabase (no partition path)`() {
        val errors = validateInline(
            """
            type Query { hello: String }
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
    fun `valid - PageInfo absent (Viaduct auto-generates it)`() {
        val errors = validateInline("type Query { hello: String }")
        errors.shouldBeEmpty()
    }

    @Test
    fun `invalid - PageInfo defined inside a module partition`() {
        val errors = validateFiles(
            "/validation/application/query.graphql",
            "/validation/partition/testmodule/graphql/page_info.graphql",
        )
        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.PAGE_INFO_DEFINED_IN_MODULE
        errors[0].message shouldContain "testmodule"
        errors[0].message shouldContain "schemabase"
    }
}
