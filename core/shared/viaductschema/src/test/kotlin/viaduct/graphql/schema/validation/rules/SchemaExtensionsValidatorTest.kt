package viaduct.graphql.schema.validation.rules

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.graphql.schema.validation.ValidationErrorCodes

class SchemaExtensionsValidatorTest {
    @Test
    fun `valid extensions-only schema passes validation`() {
        val schema = ViaductSchema.fromTypeDefinitionRegistry(
            """
            directive @resolver(isSelective: Boolean! = false, isBatching: Boolean! = false) on OBJECT | FIELD_DEFINITION
            type Query {
                hello: String
                count: Int
            }
            """.trimIndent()
        )

        val errors = SchemaExtensionsValidator.validate(schema)

        errors.shouldBeEmpty()
    }

    @Test
    fun `PageInfo defined in schemabase is not an error`() {
        val schema = ViaductSchema.fromTypeDefinitionRegistry(
            listOf(javaClass.getResource("/validation/application/page_info.graphql")!!)
        )

        val errors = SchemaExtensionsValidator.validate(schema)

        errors.shouldBeEmpty()
    }

    @Test
    fun `custom scalar and subscription are still flagged`() {
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

        val errors = SchemaExtensionsValidator.validate(schema)

        errors shouldHaveSize 2
        errors.map { it.code } shouldContainExactlyInAnyOrder listOf(
            ValidationErrorCodes.CUSTOM_SCALAR_NOT_ALLOWED,
            ValidationErrorCodes.SUBSCRIPTION_NOT_ALLOWED,
        )
    }

    @Test
    fun `NoCrossModuleInputExtensionsRule is not applied in extensions mode`() {
        val schemabaseUrl = javaClass.getResource("/validation/application/schemabase_types.graphql")!!
        val schema = ViaductSchema.fromTypeDefinitionRegistry(listOf(schemabaseUrl))

        val errors = SchemaExtensionsValidator.validate(schema)

        errors.shouldBeEmpty()
    }

    @Test
    fun `ApplicationOnlyDefinitionsRule is not applied in extensions mode`() {
        val moduleDirectiveUrl = javaClass.getResource("/validation/partition/testmodule/graphql/directives.graphql")!!
        val schema = ViaductSchema.fromTypeDefinitionRegistry(listOf(moduleDirectiveUrl))

        // In extensions mode, ApplicationOnlyDefinitionsRule is absent — directive in partition path is not flagged
        val errors = SchemaExtensionsValidator.validate(schema)

        errors.shouldBeEmpty()
    }
}
