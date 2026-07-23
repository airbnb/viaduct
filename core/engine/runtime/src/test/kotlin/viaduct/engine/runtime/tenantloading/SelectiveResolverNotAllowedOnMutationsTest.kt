package viaduct.engine.runtime.tenantloading

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.Coordinate
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockSchema

class SelectiveResolverNotAllowedOnMutationsTest {
    private val schema = MockSchema.mk(
        """
        extend type Query {
            empty: Int
        }
        extend type Mutation {
            rootMutation(id: ID!): String
            stayFoo: StayFooMutations
        }
        type StayFooMutations @namespaceType {
            doThing(id: ID!): String
        }
        type User {
            id: ID!
            name: String
        }
        """.trimIndent()
    )

    @Test
    fun `selective resolver on root Mutation field throws`() {
        assertThrows<Exception> {
            validate("Mutation", "rootMutation", isSelective = true)
        }
    }

    @Test
    fun `selective resolver on mutation namespace-type field throws`() {
        assertThrows<Exception> {
            validate("StayFooMutations", "doThing", isSelective = true)
        }
    }

    @Test
    fun `selective resolver on Query field passes`() {
        assertDoesNotThrow {
            validate("User", "name", isSelective = true)
        }
    }

    @Test
    fun `non-selective resolver on a mutation namespace field passes`() {
        assertDoesNotThrow {
            validate("StayFooMutations", "doThing", isSelective = false)
        }
    }

    @Test
    fun `non-selective resolver on a root Mutation field passes`() {
        assertDoesNotThrow {
            validate("Mutation", "rootMutation", isSelective = false)
        }
    }

    private fun validate(
        typeName: String,
        fieldName: String,
        isSelective: Boolean,
    ) {
        val resolver = MockFieldUnbatchedResolverExecutor(
            isSelective = isSelective,
            resolverId = "$typeName.$fieldName",
        )
        val ctx = FieldResolverExecutorValidationCtx(
            coord = Coordinate(typeName, fieldName),
            executor = resolver,
        )
        SelectiveResolverNotAllowedOnMutations(schema).validate(ctx)
    }
}
