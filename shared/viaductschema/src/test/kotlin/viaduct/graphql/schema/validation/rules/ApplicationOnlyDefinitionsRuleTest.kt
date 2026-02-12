package viaduct.graphql.schema.validation.rules

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.graphql.schema.validation.SchemaValidator
import viaduct.graphql.schema.validation.ValidationErrorCodes

class ApplicationOnlyDefinitionsRuleTest {
    private fun rule(modulePathPattern: String = "partition/") = ApplicationOnlyDefinitionsRule(modulePathPattern = modulePathPattern)

    @Test
    fun `should pass when directive is defined at application level`() {
        val sdl = """
            directive @auth on FIELD_DEFINITION
            type Query { hello: String }
        """.trimIndent()
        val schema = ViaductSchema.fromTypeDefinitionRegistry(sdl)
        val validator = SchemaValidator(listOf(listOf(rule())))

        val errors = validator.validate(schema)

        errors.filter { it.code == ValidationErrorCodes.DIRECTIVE_DEFINED_IN_MODULE }.shouldBeEmpty()
    }

    @Test
    fun `should pass when using built-in scalars`() {
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
        val validator = SchemaValidator(listOf(listOf(rule())))

        val errors = validator.validate(schema)

        errors.filter { it.code == ValidationErrorCodes.SCALAR_DEFINED_IN_MODULE }.shouldBeEmpty()
    }

    @Test
    fun `should fail when directive is defined in module partition`() {
        val moduleDirectiveUrl = javaClass.getResource("/validation/partition/testmodule/graphql/directives.graphql")!!
        val queryUrl = javaClass.getResource("/validation/application/query.graphql")!!
        val schema = ViaductSchema.fromTypeDefinitionRegistry(listOf(queryUrl, moduleDirectiveUrl))
        val validator = SchemaValidator(listOf(listOf(rule())))

        val errors = validator.validate(schema)

        errors.map { it.code } shouldContain ValidationErrorCodes.DIRECTIVE_DEFINED_IN_MODULE
        val directiveError = errors.first { it.code == ValidationErrorCodes.DIRECTIVE_DEFINED_IN_MODULE }
        directiveError.message shouldContain "@testDirective"
        directiveError.location.sourceLocation?.sourceName shouldContain "partition/"
    }

    @Test
    fun `should fail when scalar is defined in module partition`() {
        val moduleScalarUrl = javaClass.getResource("/validation/partition/testmodule/graphql/scalars.graphql")!!
        val queryUrl = javaClass.getResource("/validation/application/query.graphql")!!
        val schema = ViaductSchema.fromTypeDefinitionRegistry(listOf(queryUrl, moduleScalarUrl))
        val validator = SchemaValidator(listOf(listOf(rule())))

        val errors = validator.validate(schema)

        errors.map { it.code } shouldContain ValidationErrorCodes.SCALAR_DEFINED_IN_MODULE
        errors.first { it.code == ValidationErrorCodes.SCALAR_DEFINED_IN_MODULE }.message shouldContain "TestScalar"
    }

    @Test
    fun `should use custom modulePathPattern when provided`() {
        val moduleDirectiveUrl = javaClass.getResource("/validation/partition/testmodule/graphql/directives.graphql")!!
        val queryUrl = javaClass.getResource("/validation/application/query.graphql")!!
        val schema = ViaductSchema.fromTypeDefinitionRegistry(listOf(queryUrl, moduleDirectiveUrl))
        val validator = SchemaValidator(listOf(listOf(rule("non-matching-pattern/"))))

        val errors = validator.validate(schema)

        errors.filter { it.code == ValidationErrorCodes.DIRECTIVE_DEFINED_IN_MODULE }.shouldBeEmpty()
    }
}
