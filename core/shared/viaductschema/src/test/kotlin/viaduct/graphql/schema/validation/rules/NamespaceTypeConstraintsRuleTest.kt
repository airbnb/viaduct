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

class NamespaceTypeConstraintsRuleTest {
    private val preamble = """
        directive @namespaceType on OBJECT
    """.trimIndent()

    private fun validate(sdl: String) =
        SchemaValidator(listOf(listOf(NamespaceTypeConstraintsRule())))
            .validate(ViaductSchema.fromTypeDefinitionRegistry("$preamble\n$sdl"))

    @Test
    fun `valid - no errors`() {
        val errors = validate(
            """
            type Query { listings: Listings }
            type Listings @namespaceType { availableRoomTypes: [RoomType] }
            type RoomType { id: ID! }
            type NotNamespaceType { id: String }
            union SearchResult = NotNamespaceType
            """.trimIndent()
        )

        errors.shouldBeEmpty()
    }

    @Test
    fun `valid - nested namespace types`() {
        val errors = validate(
            """
            type Query { listings: Listings }
            type Listings @namespaceType { pricing: ListingsPricing }
            type ListingsPricing @namespaceType { currencyOptions: [String] }
            """.trimIndent()
        )

        errors.shouldBeEmpty()
    }

    @Test
    fun `invalid - field returning namespace type has arguments`() {
        val errors = validate(
            """
            type Query { listings(region: String!): Listings }
            type Listings @namespaceType { availableRoomTypes: [RoomType] }
            type RoomType { id: ID! }
            """.trimIndent()
        )

        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.NAMESPACE_TYPE_FIELD_HAS_ARGS
        errors[0].message shouldContain "Query.listings"
        errors[0].message shouldContain "Listings"
    }

    @Test
    fun `invalid - field returning namespace type is a list`() {
        val errors = validate(
            """
            type Query { listings: [Listings] }
            type Listings @namespaceType { availableRoomTypes: [RoomType] }
            type RoomType { id: ID! }
            """.trimIndent()
        )

        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.NAMESPACE_TYPE_FIELD_IS_LIST
        errors[0].message shouldContain "Query.listings"
        errors[0].message shouldContain "Listings"
    }

    @Test
    fun `invalid - namespace type is a union member`() {
        val errors = validate(
            """
            type Query { result: SearchResult }
            type Listings @namespaceType { availableRoomTypes: [RoomType] }
            type RoomType { id: ID! }
            union SearchResult = Listings
            """.trimIndent()
        )

        errors shouldHaveSize 2
        errors.map { it.code } shouldContainExactlyInAnyOrder listOf(
            ValidationErrorCodes.NAMESPACE_TYPE_IN_UNION,
            ValidationErrorCodes.NAMESPACE_TYPE_PARENT_COUNT_NOT_ONE
        )
        val inUnionError = errors.first { it.code == ValidationErrorCodes.NAMESPACE_TYPE_IN_UNION }
        inUnionError.message shouldContain "Listings"
        inUnionError.message shouldContain "SearchResult"
    }

    @Test
    fun `invalid - namespace type is never used as a field`() {
        val errors = validate(
            """
            type Query { name: String }
            type Listings @namespaceType { availableRoomTypes: [RoomType] }
            type RoomType { id: ID! }
            """.trimIndent()
        )

        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.NAMESPACE_TYPE_PARENT_COUNT_NOT_ONE
        errors[0].message shouldContain "Listings"
    }

    @Test
    fun `invalid - namespace type is returned by two fields on the same type`() {
        val errors = validate(
            """
            type Query { listings1: Listings, listings2: Listings }
            type Listings @namespaceType { availableRoomTypes: [RoomType] }
            type RoomType { id: ID! }
            """.trimIndent()
        )

        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.NAMESPACE_TYPE_PARENT_COUNT_NOT_ONE
        errors[0].message shouldContain "Listings"
        errors[0].message shouldContain "Query.listings1"
        errors[0].message shouldContain "Query.listings2"
    }

    @Test
    fun `invalid - namespace type is returned from fields on different valid parent types`() {
        val errors = validate(
            """
            type Query { listings: Listings, pricing: ListingsPricing }
            type Listings @namespaceType { pricing: ListingsPricing }
            type ListingsPricing @namespaceType { currencyOptions: [String] }
            """.trimIndent()
        )

        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.NAMESPACE_TYPE_PARENT_COUNT_NOT_ONE
        errors[0].message shouldContain "ListingsPricing"
    }

    @Test
    fun `invalid - namespace type returned by non-root non-namespace parent`() {
        val errors = validate(
            """
            type Query { roomType: RoomType }
            type RoomType { listings: Listings }
            type Listings @namespaceType { availableRoomTypes: [String] }
            """.trimIndent()
        )

        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.NAMESPACE_TYPE_INVALID_PARENT
        errors[0].message shouldContain "RoomType.listings"
        errors[0].message shouldContain "Listings"
    }

    @Test
    fun `invalid - namespace type field is non-null`() {
        val errors = validate(
            """
            type Query { listings: Listings! }
            type Listings @namespaceType { availableRoomTypes: [RoomType] }
            type RoomType { id: ID! }
            """.trimIndent()
        )

        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.NAMESPACE_TYPE_FIELD_IS_NON_NULL
        errors[0].message shouldContain "Query.listings"
        errors[0].message shouldContain "Listings"
    }

    @Test
    fun `valid - namespace type under mutation root`() {
        val errors = validate(
            """
            type Query { name: String }
            type Mutation { listings: Listings }
            type Listings @namespaceType { createListing: String }
            """.trimIndent()
        )

        errors.shouldBeEmpty()
    }

    @Test
    fun `valid - nested namespace types under mutation root`() {
        val errors = validate(
            """
            type Query { name: String }
            type Mutation { listings: Listings }
            type Listings @namespaceType { pricing: ListingsPricing }
            type ListingsPricing @namespaceType { setCurrency: String }
            """.trimIndent()
        )

        errors.shouldBeEmpty()
    }

    @Test
    fun `invalid - namespace type returned by non-root non-namespace parent still fails with mutation present`() {
        val errors = validate(
            """
            type Query { name: String }
            type Mutation { doSomething: String }
            type RoomType { listings: Listings }
            type Listings @namespaceType { availableRoomTypes: [String] }
            """.trimIndent()
        )

        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.NAMESPACE_TYPE_INVALID_PARENT
        errors[0].message shouldContain "RoomType.listings"
    }

    @Test
    fun `invalid - namespace type returned from both query and mutation roots`() {
        val errors = validate(
            """
            type Query { listings: Listings }
            type Mutation { listings: Listings }
            type Listings @namespaceType { availableRoomTypes: [String] }
            """.trimIndent()
        )

        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.NAMESPACE_TYPE_PARENT_COUNT_NOT_ONE
        errors[0].message shouldContain "Listings"
    }

    @Test
    fun `multiple violations are reported`() {
        val errors = validate(
            """
            type Query { listings(filter: String): [Listings] }
            type Listings @namespaceType { availableRoomTypes: [RoomType] }
            type RoomType { id: ID! }
            """.trimIndent()
        )

        errors shouldHaveSize 2
        errors.map { it.code } shouldBe listOf(
            ValidationErrorCodes.NAMESPACE_TYPE_FIELD_HAS_ARGS,
            ValidationErrorCodes.NAMESPACE_TYPE_FIELD_IS_LIST
        )
    }
}
