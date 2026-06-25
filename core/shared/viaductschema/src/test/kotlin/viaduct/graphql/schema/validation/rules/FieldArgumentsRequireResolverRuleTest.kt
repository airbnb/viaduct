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

class FieldArgumentsRequireResolverRuleTest {
    private val preamble = """
        directive @resolver(isSelective: Boolean! = false, isBatching: Boolean! = false) on OBJECT | FIELD_DEFINITION
        directive @namespaceType on OBJECT
    """.trimIndent()

    private fun validate(sdl: String) =
        SchemaValidator(listOf(listOf(FieldArgumentsRequireResolverRule())))
            .validate(ViaductSchema.fromTypeDefinitionRegistry("$preamble\n$sdl"))

    @Test
    fun `valid - object field with arguments has field resolver`() {
        val errors = validate(
            """
            type Query {
                search(query: String!): String @resolver
            }
            """.trimIndent()
        )

        errors.shouldBeEmpty()
    }

    @Test
    fun `valid - object field without arguments does not need resolver`() {
        val errors = validate(
            """
            type Query {
                greeting: String
            }
            """.trimIndent()
        )

        errors.shouldBeEmpty()
    }

    @Test
    fun `invalid - object field with arguments is missing field resolver`() {
        val errors = validate(
            """
            type Query {
                search(query: String!): String
            }
            """.trimIndent()
        )

        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.FIELD_WITH_ARGS_MISSING_RESOLVER
        errors[0].message shouldContain "Query.search"
        errors[0].message shouldContain "@resolver"
    }

    @Test
    fun `invalid - object resolver does not satisfy argument field resolver requirement`() {
        val errors = validate(
            """
            type Query {
                user: User
            }

            type User @resolver {
                photo(size: Int!): String
            }
            """.trimIndent()
        )

        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.FIELD_WITH_ARGS_MISSING_RESOLVER
        errors[0].message shouldContain "User.photo"
    }

    @Test
    fun `invalid - every argument field missing resolver is reported`() {
        val errors = validate(
            """
            type Query {
                search(query: String!): String
                lookup(id: ID!): String
                greeting: String
            }

            type Mutation {
                update(id: ID!): String
            }
            """.trimIndent()
        )

        errors shouldHaveSize 3
        errors.map { it.code } shouldContainExactlyInAnyOrder listOf(
            ValidationErrorCodes.FIELD_WITH_ARGS_MISSING_RESOLVER,
            ValidationErrorCodes.FIELD_WITH_ARGS_MISSING_RESOLVER,
            ValidationErrorCodes.FIELD_WITH_ARGS_MISSING_RESOLVER
        )
        errors.map { it.message }.any { it.contains("Query.search") } shouldBe true
        errors.map { it.message }.any { it.contains("Query.lookup") } shouldBe true
        errors.map { it.message }.any { it.contains("Mutation.update") } shouldBe true
    }

    @Test
    fun `valid - namespace fields with arguments are handled by namespace validation`() {
        val errors = validate(
            """
            type Query {
                listings(region: String!): Listings
            }

            type Listings @namespaceType {
                placeholder: String
            }
            """.trimIndent()
        )

        errors.shouldBeEmpty()
    }

    @Test
    fun `invalid - regular fields inside namespace types still need resolver when they have arguments`() {
        val errors = validate(
            """
            type Query {
                listings: Listings
            }

            type Listings @namespaceType {
                search(query: String!): String
            }
            """.trimIndent()
        )

        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.FIELD_WITH_ARGS_MISSING_RESOLVER
        errors[0].message shouldContain "Listings.search"
    }
}
