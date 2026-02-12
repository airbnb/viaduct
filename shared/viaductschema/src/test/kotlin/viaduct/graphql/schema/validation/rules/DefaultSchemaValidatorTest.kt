package viaduct.graphql.schema.validation.rules

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.graphql.schema.validation.ValidationErrorCodes

class DefaultSchemaValidatorTest {
    @Test
    fun `should produce no errors for valid schema`() {
        val schema = ViaductSchema.fromTypeDefinitionRegistry(
            """
            type Query {
                hello: String
                count: Int
            }
            type Mutation {
                setMessage(msg: String): String
            }
            """.trimIndent()
        )

        val errors = DefaultSchemaValidator.validate(schema)

        errors.shouldBeEmpty()
    }

    @Test
    fun `should detect subscription and custom scalar violations`() {
        val schema = ViaductSchema.fromTypeDefinitionRegistry(
            """
            scalar DateTime
            type Query { time: DateTime }
            type Subscription { onTick: String }
            schema {
                query: Query
                subscription: Subscription
            }
            """.trimIndent()
        )

        val errors = DefaultSchemaValidator.validate(schema)

        val codes = errors.map { it.code }
        codes.count { it == ValidationErrorCodes.SUBSCRIPTION_NOT_ALLOWED } shouldBe 1
        codes.count { it == ValidationErrorCodes.CUSTOM_SCALAR_NOT_ALLOWED } shouldBe 1
    }

    @Test
    fun `should detect module-level directive and scalar violations`() {
        val moduleDirectiveUrl = javaClass.getResource("/validation/partition/testmodule/graphql/directives.graphql")!!
        val moduleScalarUrl = javaClass.getResource("/validation/partition/testmodule/graphql/scalars.graphql")!!
        val schema = ViaductSchema.fromTypeDefinitionRegistry(listOf(moduleDirectiveUrl, moduleScalarUrl))

        val errors = DefaultSchemaValidator.validate(schema)

        val codes = errors.map { it.code }
        codes.count { it == ValidationErrorCodes.DIRECTIVE_DEFINED_IN_MODULE } shouldBe 1
        codes.count { it == ValidationErrorCodes.SCALAR_DEFINED_IN_MODULE } shouldBe 1
    }

    @Test
    fun `create should return a functional SchemaValidator`() {
        val validator = DefaultSchemaValidator.create()
        val schema = ViaductSchema.fromTypeDefinitionRegistry(
            """
            scalar Custom
            type Query { data: Custom }
            """.trimIndent()
        )

        val errors = validator.validate(schema)

        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.CUSTOM_SCALAR_NOT_ALLOWED
    }
}
