package viaduct.graphql.schema.validation.rules

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
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
            scalar URL
            type Query { link: URL }
            type Subscription { onTick: String }
            schema {
                query: Query
                subscription: Subscription
            }
            """.trimIndent()
        )

        val errors = DefaultSchemaValidator.validate(schema)

        errors shouldHaveSize 2
        errors.map { it.code } shouldContainExactlyInAnyOrder listOf(
            ValidationErrorCodes.SUBSCRIPTION_NOT_ALLOWED,
            ValidationErrorCodes.CUSTOM_SCALAR_NOT_ALLOWED
        )
    }

    @Test
    fun `should allow Viaduct standard scalars`() {
        val schema = ViaductSchema.fromTypeDefinitionRegistry(
            """
            scalar Date
            scalar DateTime
            scalar Long
            scalar BackingData
            scalar Byte
            scalar Short
            scalar JSON
            scalar Time
            type Query { data: String }
            """.trimIndent()
        )

        val errors = DefaultSchemaValidator.validate(schema)

        errors.shouldBeEmpty()
    }

    @Test
    fun `should detect module-level directive, scalar, and custom scalar violations`() {
        val moduleDirectiveUrl = javaClass.getResource("/validation/partition/testmodule/graphql/directives.graphql")!!
        val moduleScalarUrl = javaClass.getResource("/validation/partition/testmodule/graphql/scalars.graphql")!!
        val schema = ViaductSchema.fromTypeDefinitionRegistry(listOf(moduleDirectiveUrl, moduleScalarUrl))

        val errors = DefaultSchemaValidator.validate(schema)

        errors shouldHaveSize 3
        errors.map { it.code } shouldContainExactlyInAnyOrder listOf(
            ValidationErrorCodes.DIRECTIVE_DEFINED_IN_MODULE,
            ValidationErrorCodes.SCALAR_DEFINED_IN_MODULE,
            ValidationErrorCodes.CUSTOM_SCALAR_NOT_ALLOWED
        )
    }
}
