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
            directive @resolver(isSelective: Boolean! = false, isBatching: Boolean! = false) on OBJECT | FIELD_DEFINITION
            type Query {
                hello: String
                count: Int
            }
            type Mutation {
                setMessage(msg: String): String @resolver
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

    @Test
    fun `should detect BackingData field missing @backingData directive`() {
        val schema = ViaductSchema.fromTypeDefinitionRegistry(
            """
            scalar BackingData
            directive @backingData(class: String!) on FIELD_DEFINITION
            type Query { data: BackingData }
            """.trimIndent()
        )

        val errors = DefaultSchemaValidator.validate(schema)

        errors.map { it.code } shouldContainExactlyInAnyOrder listOf(
            ValidationErrorCodes.BACKING_DATA_MISSING_DIRECTIVE
        )
    }

    @Test
    fun `should detect @idOf referencing undefined type`() {
        val schema = ViaductSchema.fromTypeDefinitionRegistry(
            """
            directive @idOf(type: String!) on FIELD_DEFINITION | INPUT_FIELD_DEFINITION | ARGUMENT_DEFINITION
            interface Node { id: ID! }
            type Query { nodeId: ID @idOf(type: "Ghost") }
            """.trimIndent()
        )

        val errors = DefaultSchemaValidator.validate(schema)

        errors.map { it.code } shouldContainExactlyInAnyOrder listOf(
            ValidationErrorCodes.ID_OF_TYPE_NOT_FOUND
        )
    }

    @Test
    fun `should detect namespace type field with arguments`() {
        val schema = ViaductSchema.fromTypeDefinitionRegistry(
            """
            directive @namespaceType on OBJECT
            type Query { listings(region: String): Listings }
            type Listings @namespaceType { placeholder: String }
            """.trimIndent()
        )

        val errors = DefaultSchemaValidator.validate(schema)

        errors.map { it.code } shouldContainExactlyInAnyOrder listOf(
            ValidationErrorCodes.NAMESPACE_TYPE_FIELD_HAS_ARGS
        )
    }

    @Test
    fun `should detect object field with arguments missing resolver`() {
        val schema = ViaductSchema.fromTypeDefinitionRegistry(
            """
            type Query { search(query: String!): String }
            """.trimIndent()
        )

        val errors = DefaultSchemaValidator.validate(schema)

        errors.map { it.code } shouldContainExactlyInAnyOrder listOf(
            ValidationErrorCodes.FIELD_WITH_ARGS_MISSING_RESOLVER
        )
    }

    @Test
    fun `should detect connection type missing edges field`() {
        val schema = ViaductSchema.fromTypeDefinitionRegistry(
            """
            directive @connection on OBJECT
            directive @edge on OBJECT
            type PageInfo {
                hasNextPage: Boolean!
                hasPreviousPage: Boolean!
                startCursor: String
                endCursor: String
            }
            type MyConnection @connection { pageInfo: PageInfo! }
            type Query { items: MyConnection }
            """.trimIndent()
        )

        val errors = DefaultSchemaValidator.validate(schema)

        errors.map { it.code } shouldContainExactlyInAnyOrder listOf(
            ValidationErrorCodes.CONNECTION_MISSING_EDGES_FIELD
        )
    }

    @Test
    fun `should detect resolver on interface field`() {
        val schema = ViaductSchema.fromTypeDefinitionRegistry(
            """
            directive @resolver on FIELD_DEFINITION
            type Query { entity: Entity }
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

        val errors = DefaultSchemaValidator.validate(schema)

        errors.map { it.code } shouldContainExactlyInAnyOrder listOf(
            ValidationErrorCodes.RESOLVER_ON_INTERFACE_FIELD
        )
    }
}
