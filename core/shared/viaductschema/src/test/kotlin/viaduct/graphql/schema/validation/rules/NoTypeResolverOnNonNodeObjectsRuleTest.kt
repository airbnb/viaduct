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

class NoTypeResolverOnNonNodeObjectsRuleTest {
    private val preamble = """
        directive @resolver on FIELD_DEFINITION | OBJECT

        interface Node {
            id: ID!
        }
    """.trimIndent()

    private fun validate(sdl: String) =
        SchemaValidator(listOf(listOf(NoTypeResolverOnNonNodeObjectsRule())))
            .validate(ViaductSchema.fromTypeDefinitionRegistry("$preamble\n$sdl"))

    @Test
    fun `valid - node type may declare type-level resolver`() {
        val errors = validate(
            """
            type Query {
                user: User
            }

            type User implements Node @resolver {
                id: ID!
                name: String
            }
            """.trimIndent()
        )

        errors.shouldBeEmpty()
    }

    @Test
    fun `valid - non-node object may declare field-level resolver`() {
        val errors = validate(
            """
            type Query {
                profile: Profile
            }

            type Profile {
                name: String @resolver
            }
            """.trimIndent()
        )

        errors.shouldBeEmpty()
    }

    @Test
    fun `invalid - non-node object cannot declare type-level resolver`() {
        val errors = validate(
            """
            type Query {
                profile: Profile
            }

            type Profile @resolver {
                name: String
            }
            """.trimIndent()
        )

        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.RESOLVER_ON_NON_NODE_OBJECT
        errors[0].message shouldContain "Profile"
        errors[0].message shouldContain "@resolver"
        errors[0].message shouldContain "Node"
    }

    @Test
    fun `invalid - extended type cannot declare type-level resolver without node`() {
        val errors = validate(
            """
            type Query {
                profile: Profile
            }

            type Profile {
                name: String
            }

            extend type Profile @resolver
            """.trimIndent()
        )

        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.RESOLVER_ON_NON_NODE_OBJECT
        errors[0].message shouldContain "Profile"
    }

    @Test
    fun `invalid - error location points to the extension file, not the base type file`() {
        val baseUrl = javaClass.getResource("/validation/application/resolver_non_node_base.graphql")!!
        val extensionUrl = javaClass.getResource("/validation/application/resolver_non_node_extension.graphql")!!
        val schema = ViaductSchema.fromTypeDefinitionRegistry(listOf(baseUrl, extensionUrl))

        val errors = SchemaValidator(listOf(listOf(NoTypeResolverOnNonNodeObjectsRule()))).validate(schema)

        errors shouldHaveSize 1
        errors[0].location.sourceLocation.shouldNotBeNull().sourceName shouldContain "resolver_non_node_extension.graphql"
    }
}
