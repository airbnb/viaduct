package viaduct.graphql.schema.validation.rules

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.graphql.schema.validation.GraphQLBuiltIns
import viaduct.graphql.schema.validation.SchemaValidator
import viaduct.graphql.schema.validation.ValidationErrorCodes

class NoCustomScalarsRuleTest {
    @Test
    fun `should pass when schema only uses built-in scalars`() {
        val sdl = """
            type Query {
                name: String
                age: Int
                score: Float
                active: Boolean
                id: ID
            }
        """.trimIndent()
        val schema = ViaductSchema.fromTypeDefinitionRegistry(sdl)
        val validator = SchemaValidator(listOf(listOf(NoCustomScalarsRule())))

        val errors = validator.validate(schema)

        errors.shouldBeEmpty()
    }

    @Test
    fun `should fail when schema defines custom scalar`() {
        val sdl = """
            scalar DateTime
            type Query {
                createdAt: DateTime
            }
        """.trimIndent()
        val schema = ViaductSchema.fromTypeDefinitionRegistry(sdl)
        val validator = SchemaValidator(listOf(listOf(NoCustomScalarsRule())))

        val errors = validator.validate(schema)

        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.CUSTOM_SCALAR_NOT_ALLOWED
        errors[0].location.path shouldBe listOf("DateTime")
    }

    @Test
    fun `should report multiple errors when schema defines multiple custom scalars`() {
        val sdl = """
            scalar DateTime
            scalar URL
            scalar JSON
            type Query {
                data: String
            }
        """.trimIndent()
        val schema = ViaductSchema.fromTypeDefinitionRegistry(sdl)
        val validator = SchemaValidator(listOf(listOf(NoCustomScalarsRule())))

        val errors = validator.validate(schema)

        errors shouldHaveSize 3
        errors.map { it.location.path.first() } shouldContainExactlyInAnyOrder listOf("DateTime", "URL", "JSON")
    }

    @Test
    fun `should include allowed scalars in error message`() {
        val sdl = """
            scalar Custom
            type Query { data: Custom }
        """.trimIndent()
        val schema = ViaductSchema.fromTypeDefinitionRegistry(sdl)
        val validator = SchemaValidator(listOf(listOf(NoCustomScalarsRule())))

        val errors = validator.validate(schema)

        errors[0].message shouldContain "Boolean"
        errors[0].message shouldContain "Float"
        errors[0].message shouldContain "ID"
        errors[0].message shouldContain "Int"
        errors[0].message shouldContain "String"
    }

    @Test
    fun `should allow scalar when included in custom builtInScalars set`() {
        val sdl = """
            scalar DateTime
            type Query { createdAt: DateTime }
        """.trimIndent()
        val schema = ViaductSchema.fromTypeDefinitionRegistry(sdl)
        val customBuiltInScalars = GraphQLBuiltIns.SCALARS + "DateTime"
        val validator = SchemaValidator(listOf(listOf(NoCustomScalarsRule(builtInScalars = customBuiltInScalars))))

        val errors = validator.validate(schema)

        errors.shouldBeEmpty()
    }
}
