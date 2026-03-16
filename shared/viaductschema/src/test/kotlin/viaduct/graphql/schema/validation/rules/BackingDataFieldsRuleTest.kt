package viaduct.graphql.schema.validation.rules

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.graphql.schema.validation.SchemaValidator
import viaduct.graphql.schema.validation.ValidationErrorCodes

class BackingDataFieldsRuleTest {
    private val preamble = """
        scalar BackingData
        directive @backingData(class: String!) on FIELD_DEFINITION
    """.trimIndent()

    private fun validate(sdl: String) =
        SchemaValidator(listOf(listOf(BackingDataFieldsRule())))
            .validate(ViaductSchema.fromTypeDefinitionRegistry("$preamble\n$sdl"))

    @Test
    fun `should pass when BackingData field has @backingData directive`() {
        val errors = validate(
            """
            type Query {
                plain: String
                data: BackingData @backingData(class: "MyData")
            }
            """.trimIndent()
        )

        errors.shouldBeEmpty()
    }

    @Test
    fun `should fail when BackingData field on object type is missing @backingData directive`() {
        val errors = validate(
            """
            type Query {
                data: BackingData
            }
            """.trimIndent()
        )

        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.BACKING_DATA_MISSING_DIRECTIVE
        errors[0].message shouldContain "Query.data"
        errors[0].message shouldContain "Missing @backingData directive"
    }

    @Test
    fun `should fail when @backingData directive is used without BackingData type on object type`() {
        val errors = validate(
            """
            type Query {
                data: String @backingData(class: "MyData")
            }
            """.trimIndent()
        )

        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.BACKING_DATA_MISSING_TYPE
        errors[0].message shouldContain "Query.data"
        errors[0].message shouldContain "Missing BackingData type"
    }

    @Test
    fun `should fail for both directions of violation on object type extensions`() {
        val errors = validate(
            """
            type Query { placeholder: String }
            extend type Query {
                missingDirective: BackingData
                missingType: String @backingData(class: "MyData")
            }
            """.trimIndent()
        )

        errors shouldHaveSize 2
        errors.map { it.code } shouldContainExactlyInAnyOrder listOf(
            ValidationErrorCodes.BACKING_DATA_MISSING_DIRECTIVE,
            ValidationErrorCodes.BACKING_DATA_MISSING_TYPE
        )
        errors.map { it.message }.any { it.contains("Query.missingDirective") } shouldBe true
        errors.map { it.message }.any { it.contains("Query.missingType") } shouldBe true
    }

    @Test
    fun `should fail for both directions of violation on interface type`() {
        val errors = validate(
            """
            type Query { placeholder: String }
            interface MyInterface {
                missingDirective: BackingData
                missingType: String @backingData(class: "MyData")
            }
            extend interface MyInterface {
                extMissingDirective: BackingData
                extMissingType: String @backingData(class: "MyData")
            }
            """.trimIndent()
        )

        errors shouldHaveSize 4
        errors.map { it.code } shouldContainExactlyInAnyOrder listOf(
            ValidationErrorCodes.BACKING_DATA_MISSING_DIRECTIVE,
            ValidationErrorCodes.BACKING_DATA_MISSING_TYPE,
            ValidationErrorCodes.BACKING_DATA_MISSING_DIRECTIVE,
            ValidationErrorCodes.BACKING_DATA_MISSING_TYPE
        )
    }

    @Test
    fun `should fail when BackingData type is used on an input field`() {
        val errors = validate(
            """
            type Query { placeholder: String }
            input MyInput {
                field: BackingData
            }
            extend input MyInput {
                extField: BackingData
            }
            """.trimIndent()
        )

        errors shouldHaveSize 2
        errors[0].code shouldBe ValidationErrorCodes.BACKING_DATA_ON_INPUT_FIELD
        errors[0].message shouldContain "MyInput.field"
        errors[0].message shouldContain "input field"
        errors[1].code shouldBe ValidationErrorCodes.BACKING_DATA_ON_INPUT_FIELD
        errors[1].message shouldContain "MyInput.extField"
    }
}
