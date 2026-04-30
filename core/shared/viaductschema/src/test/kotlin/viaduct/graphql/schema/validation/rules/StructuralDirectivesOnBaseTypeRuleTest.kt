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

class StructuralDirectivesOnBaseTypeRuleTest {
    private val preamble = """
        directive @connection on OBJECT
        directive @edge on OBJECT
        directive @namespaceType on OBJECT
    """.trimIndent()

    private fun validate(sdl: String) =
        SchemaValidator(listOf(listOf(StructuralDirectivesOnBaseTypeRule())))
            .validate(ViaductSchema.fromTypeDefinitionRegistry("$preamble\n$sdl"))

    @Test
    fun `valid - @connection on base type definition`() {
        val errors = validate(
            """
            type MyConnection @connection { name: String }
            type Query { items: MyConnection }
            """.trimIndent()
        )
        errors.shouldBeEmpty()
    }

    @Test
    fun `valid - @edge on base type definition`() {
        val errors = validate(
            """
            type MyEdge @edge { node: String }
            type Query { placeholder: String }
            """.trimIndent()
        )
        errors.shouldBeEmpty()
    }

    @Test
    fun `valid - @namespaceType on base type definition`() {
        val errors = validate(
            """
            type MyNamespace @namespaceType { name: String }
            type Query { ns: MyNamespace }
            """.trimIndent()
        )
        errors.shouldBeEmpty()
    }

    @Test
    fun `valid - extend type without structural directive is allowed`() {
        val errors = validate(
            """
            type MyConnection @connection { name: String }
            extend type MyConnection { extra: String }
            type Query { items: MyConnection }
            """.trimIndent()
        )
        errors.shouldBeEmpty()
    }

    @Test
    fun `invalid - @connection on extend type`() {
        val errors = validate(
            """
            type MyConnection { name: String }
            extend type MyConnection @connection { extra: String }
            type Query { items: MyConnection }
            """.trimIndent()
        )
        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.STRUCTURAL_DIRECTIVE_ON_EXTENSION
        errors[0].message shouldContain "@connection"
        errors[0].message shouldContain "MyConnection"
    }

    @Test
    fun `invalid - @edge on extend type`() {
        val errors = validate(
            """
            type MyEdge { cursor: String }
            extend type MyEdge @edge { node: String }
            type Query { placeholder: String }
            """.trimIndent()
        )
        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.STRUCTURAL_DIRECTIVE_ON_EXTENSION
        errors[0].message shouldContain "@edge"
        errors[0].message shouldContain "MyEdge"
    }

    @Test
    fun `invalid - @namespaceType on extend type`() {
        val errors = validate(
            """
            type MyNamespace { name: String }
            extend type MyNamespace @namespaceType { extra: String }
            type Query { ns: MyNamespace }
            """.trimIndent()
        )
        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.STRUCTURAL_DIRECTIVE_ON_EXTENSION
        errors[0].message shouldContain "@namespaceType"
        errors[0].message shouldContain "MyNamespace"
    }
}
