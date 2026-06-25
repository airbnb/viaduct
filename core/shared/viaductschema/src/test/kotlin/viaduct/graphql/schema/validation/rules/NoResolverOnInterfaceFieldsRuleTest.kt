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

class NoResolverOnInterfaceFieldsRuleTest {
    private val preamble = """
        directive @resolver on FIELD_DEFINITION
    """.trimIndent()

    private fun validate(sdl: String) =
        SchemaValidator(listOf(listOf(NoResolverOnInterfaceFieldsRule())))
            .validate(ViaductSchema.fromTypeDefinitionRegistry("$preamble\n$sdl"))

    @Test
    fun `valid - object fields may declare resolver`() {
        val errors = validate(
            """
            type Query {
                entity: User
            }

            interface Entity {
                id: ID!
            }

            type User implements Entity {
                id: ID!
                name: String @resolver
            }
            """.trimIndent()
        )

        errors.shouldBeEmpty()
    }

    @Test
    fun `invalid - interface field cannot declare resolver`() {
        val errors = validate(
            """
            type Query {
                entity: Entity
            }

            interface Entity {
                id: ID!
                displayName: String @resolver
            }

            type User implements Entity {
                id: ID!
                displayName: String
            }
            """.trimIndent()
        )

        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.RESOLVER_ON_INTERFACE_FIELD
        errors[0].message shouldContain "Entity.displayName"
        errors[0].message shouldContain "@resolver"
        errors[0].message shouldContain "concrete object fields"
    }

    @Test
    fun `invalid - extended interface fields cannot declare resolver`() {
        val errors = validate(
            """
            type Query {
                entity: Entity
            }

            interface Entity {
                id: ID!
            }

            extend interface Entity {
                displayName: String @resolver
                profilePhotoUrl: String @resolver
            }

            type User implements Entity {
                id: ID!
                displayName: String
                profilePhotoUrl: String
            }
            """.trimIndent()
        )

        errors shouldHaveSize 2
        errors.map { it.code } shouldContainExactlyInAnyOrder listOf(
            ValidationErrorCodes.RESOLVER_ON_INTERFACE_FIELD,
            ValidationErrorCodes.RESOLVER_ON_INTERFACE_FIELD
        )
        errors.map { it.message }.joinToString("\n").also { messages ->
            messages shouldContain "Entity.displayName"
            messages shouldContain "Entity.profilePhotoUrl"
            messages shouldContain "@resolver"
        }
    }
}
