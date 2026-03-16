package viaduct.graphql.schema.validation.rules

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.graphql.schema.validation.SchemaValidator
import viaduct.graphql.schema.validation.ValidationErrorCodes

class NoSubscriptionsRuleTest {
    private val validator = SchemaValidator(listOf(listOf(NoSubscriptionsRule())))

    @Test
    fun `schema without subscription passes validation`() {
        val schema = ViaductSchema.fromTypeDefinitionRegistry(
            """
            type Query { hello: String }
            type Mutation { setHello(value: String): String }
            """.trimIndent()
        )

        val errors = validator.validate(schema)

        errors.shouldBeEmpty()
    }

    @Test
    fun `schema with subscription fails with descriptive error`() {
        val schema = ViaductSchema.fromTypeDefinitionRegistry(
            """
            type Query { hello: String }
            type Subscription { onHelloChanged: String }
            """.trimIndent()
        )

        val errors = validator.validate(schema)

        errors shouldHaveSize 1
        with(errors[0]) {
            code shouldBe ValidationErrorCodes.SUBSCRIPTION_NOT_ALLOWED
            message shouldContain "Subscription"
            location.path shouldBe listOf("Subscription", "onHelloChanged")
            location.sourceLocation shouldBe null
        }
    }

    @Test
    fun `subscription type with only framework dummy field passes validation`() {
        // The framework adds a dummy `_` field to the empty subscription root type.
        // This should not be flagged as a tenant violation.
        val schema = ViaductSchema.fromTypeDefinitionRegistry(
            """
            type Query { hello: String }
            type Subscription { _: String }
            """.trimIndent()
        )

        val errors = validator.validate(schema)

        errors.shouldBeEmpty()
    }

    @Test
    fun `error location includes source location when loaded from file`() {
        val schemaUrl = javaClass.getResource("/validation/application/subscription.graphql")!!
        val schema = ViaductSchema.fromTypeDefinitionRegistry(listOf(schemaUrl))

        val errors = validator.validate(schema)

        errors shouldHaveSize 1
        errors[0].location.sourceLocation.shouldNotBeNull().sourceName shouldContain "subscription.graphql"
    }

    @Test
    fun `schema with explicit subscription type in schema definition fails validation`() {
        val schema = ViaductSchema.fromTypeDefinitionRegistry(
            """
            schema {
                query: Query
                subscription: HelloEvents
            }
            type Query { hello: String }
            type HelloEvents { onHelloChanged: String }
            """.trimIndent()
        )

        val errors = validator.validate(schema)

        errors shouldHaveSize 1
        with(errors[0]) {
            code shouldBe ValidationErrorCodes.SUBSCRIPTION_NOT_ALLOWED
            message shouldContain "HelloEvents"
            location.path shouldBe listOf("HelloEvents", "onHelloChanged")
        }
    }
}
