package viaduct.graphql.schema.validation.rules

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.graphql.schema.validation.GraphQLBuiltIns
import viaduct.graphql.schema.validation.SchemaValidator
import viaduct.graphql.schema.validation.ValidationErrorCodes

class NoCustomDirectivesRuleTest {
    @Test
    fun `should pass when schema only uses built-in directives`() {
        val sdl = """
            type Query {
                name: String @deprecated(reason: "Use newName instead")
            }
        """.trimIndent()
        val schema = ViaductSchema.fromTypeDefinitionRegistry(sdl)
        val validator = SchemaValidator(listOf(listOf(NoCustomDirectivesRule())))

        val errors = validator.validate(schema)

        errors.shouldBeEmpty()
    }

    @Test
    fun `should fail when schema defines custom directive`() {
        val sdl = """
            directive @custom on FIELD_DEFINITION
            type Query {
                data: String @custom
            }
        """.trimIndent()
        val schema = ViaductSchema.fromTypeDefinitionRegistry(sdl)
        val validator = SchemaValidator(listOf(listOf(NoCustomDirectivesRule())))

        val errors = validator.validate(schema)

        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.CUSTOM_DIRECTIVE_NOT_ALLOWED
        errors[0].location.path shouldBe listOf("@custom")
        errors[0].location.sourceLocation shouldBe null
    }

    @Test
    fun `error location includes source location when loaded from file`() {
        val schemaUrl = javaClass.getResource("/validation/application/custom_directive.graphql")!!
        val schema = ViaductSchema.fromTypeDefinitionRegistry(listOf(schemaUrl))
        val validator = SchemaValidator(listOf(listOf(NoCustomDirectivesRule())))

        val errors = validator.validate(schema)

        errors shouldHaveSize 1
        errors[0].location.sourceLocation.shouldNotBeNull().sourceName shouldContain "custom_directive.graphql"
    }

    @Test
    fun `should report multiple errors when schema defines multiple custom directives`() {
        val sdl = """
            directive @custom1 on FIELD_DEFINITION
            directive @custom2 on OBJECT
            directive @custom3 on ARGUMENT_DEFINITION
            type Query {
                data: String
            }
        """.trimIndent()
        val schema = ViaductSchema.fromTypeDefinitionRegistry(sdl)
        val validator = SchemaValidator(listOf(listOf(NoCustomDirectivesRule())))

        val errors = validator.validate(schema)

        errors shouldHaveSize 3
        errors.map { it.location.path.first() } shouldContainExactlyInAnyOrder listOf("@custom1", "@custom2", "@custom3")
    }

    @Test
    fun `should include allowed directives in error message`() {
        val sdl = """
            directive @custom on FIELD_DEFINITION
            type Query { data: String }
        """.trimIndent()
        val schema = ViaductSchema.fromTypeDefinitionRegistry(sdl)
        val validator = SchemaValidator(listOf(listOf(NoCustomDirectivesRule())))

        val errors = validator.validate(schema)

        errors[0].message shouldContain "@deprecated"
        errors[0].message shouldContain "@include"
        errors[0].message shouldContain "@skip"
    }

    @Test
    fun `should match allowed directives case-insensitively`() {
        val sdl = """
            directive @DEPRECATED(reason: String) on FIELD_DEFINITION
            directive @Skip(if: Boolean!) on FIELD
            type Query { data: String }
        """.trimIndent()
        val schema = ViaductSchema.fromTypeDefinitionRegistry(sdl)
        val validator = SchemaValidator(listOf(listOf(NoCustomDirectivesRule())))

        val errors = validator.validate(schema)

        errors.shouldBeEmpty()
    }

    @Test
    fun `should allow directive when included in custom builtInDirectives set`() {
        val sdl = """
            directive @myCustom on FIELD_DEFINITION
            type Query { data: String @myCustom }
        """.trimIndent()
        val schema = ViaductSchema.fromTypeDefinitionRegistry(sdl)
        val customBuiltInDirectives = GraphQLBuiltIns.DIRECTIVES + "myCustom"
        val validator = SchemaValidator(listOf(listOf(NoCustomDirectivesRule(builtInDirectives = customBuiltInDirectives))))

        val errors = validator.validate(schema)

        errors.shouldBeEmpty()
    }

    @Test
    fun `should pass when schema uses oneOf directive`() {
        val sdl = """
            directive @oneOf on INPUT_OBJECT
            input SearchInput @oneOf {
                byId: ID
                byName: String
            }
            type Query {
                search(input: SearchInput): String
            }
        """.trimIndent()
        val schema = ViaductSchema.fromTypeDefinitionRegistry(sdl)
        val validator = SchemaValidator(listOf(listOf(NoCustomDirectivesRule())))

        val errors = validator.validate(schema)

        errors.shouldBeEmpty()
    }

    @Test
    fun `should pass when schema uses specifiedBy directive`() {
        val sdl = """
            directive @specifiedBy(url: String!) on SCALAR
            type Query {
                data: String
            }
        """.trimIndent()
        val schema = ViaductSchema.fromTypeDefinitionRegistry(sdl)
        val validator = SchemaValidator(listOf(listOf(NoCustomDirectivesRule())))

        val errors = validator.validate(schema)

        errors.shouldBeEmpty()
    }
}
