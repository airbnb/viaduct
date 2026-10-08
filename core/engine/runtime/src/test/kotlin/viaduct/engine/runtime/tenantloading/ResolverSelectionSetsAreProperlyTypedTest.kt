package viaduct.engine.runtime.tenantloading

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.Coordinate
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.mocks.createRSS

class ResolverSelectionSetsAreProperlyTypedTest {
    @Test
    fun `valid -- no selection sets`() {
        assertValid("User", "name", null, null)
    }

    @Test
    fun `valid -- object selection set with correct type`() {
        val objectRSS = createRSS("User", "id name")
        assertValid("User", "name", objectRSS, null)
    }

    @Test
    fun `valid -- query selection set with correct type`() {
        val queryRSS = createRSS("Query", "currentUser")
        assertValid("User", "name", null, queryRSS)
    }

    @Test
    fun `valid -- both selection sets with correct types`() {
        val objectRSS = createRSS("User", "id")
        val queryRSS = createRSS("Query", "currentUser")
        assertValid("User", "name", objectRSS, queryRSS)
    }

    @Test
    fun `mutation query selections are rejected`() {
        assertInvalid("Mutation", "updateUser", null, createRSS("Query", "currentUser"), "Mutation.updateUser", "must not declare object or query selections")
    }

    @Test
    fun `namespaced mutation query selections are rejected`() {
        assertInvalid("MutationNamespace", "updateUser", null, createRSS("Query", "currentUser"), "MutationNamespace.updateUser", "must not declare object or query selections")
    }

    @Test
    fun `mutation object selections are rejected`() {
        assertInvalid("Mutation", "updateUser", createRSS("Mutation", "updateUser { id }"), null, "Mutation.updateUser", "must not declare object or query selections")
        assertInvalid("MutationNamespace", "updateUser", createRSS("MutationNamespace", "updateUser { id }"), null, "MutationNamespace.updateUser", "must not declare object or query selections")
    }

    @Test
    fun `mutation without query selections passes`() {
        assertValid("Mutation", "updateUser", null, null)
        assertValid("MutationNamespace", "updateUser", null, null)
    }

    @Test
    fun `invalid -- object selection set with wrong type`() {
        val objectRSS = createRSS("Query", "currentUser") // Wrong! Should be "User"
        assertInvalid("User", "name", objectRSS, null, "Query")
    }

    @Test
    fun `invalid -- query selection set with wrong type`() {
        val queryRSS = createRSS("User", "id name") // Wrong! Should be "Query"
        assertInvalid("User", "name", null, queryRSS, "User")
    }

    @Test
    fun `invalid -- both selection sets with wrong types`() {
        val objectRSS = createRSS("Query", "currentUser") // Wrong! Should be "User"
        val queryRSS = createRSS("User", "id") // Wrong! Should be "Query"
        assertInvalid("User", "name", objectRSS, queryRSS, "Query", "User")
    }

    /**
     * Helper method to validate resolver selection sets and assert no exception is thrown
     */
    private fun assertValid(
        typeName: String,
        fieldName: String,
        objectSelectionSet: viaduct.engine.api.RequiredSelectionSet?,
        querySelectionSet: viaduct.engine.api.RequiredSelectionSet?
    ) {
        assertDoesNotThrow {
            validateResolverSelectionSets(typeName, fieldName, objectSelectionSet, querySelectionSet)
        }
    }

    /**
     * Helper method to validate resolver selection sets and assert an exception is thrown
     * containing the specified invalid type names
     */
    private fun assertInvalid(
        typeName: String,
        fieldName: String,
        objectSelectionSet: viaduct.engine.api.RequiredSelectionSet?,
        querySelectionSet: viaduct.engine.api.RequiredSelectionSet?,
        vararg expectedInvalidTypeNames: String
    ) {
        val exception = assertThrows<Exception> {
            validateResolverSelectionSets(typeName, fieldName, objectSelectionSet, querySelectionSet)
        }

        for (invalidTypeName in expectedInvalidTypeNames) {
            assertTrue(
                exception.message!!.contains(invalidTypeName),
                "Exception message should contain invalid type name '$invalidTypeName'. " +
                    "Actual message: ${exception.message}"
            )
        }
    }

    /**
     * Creates a ResolverExecutorValidationCtx and validates it using ResolverSelectionSetsAreProperlyTyped
     */
    private fun validateResolverSelectionSets(
        typeName: String,
        fieldName: String,
        objectSelectionSet: viaduct.engine.api.RequiredSelectionSet?,
        querySelectionSet: viaduct.engine.api.RequiredSelectionSet?
    ) {
        val schema = MockSchema.mk(
            """
            extend type Query {
                empty: Int
                currentUser: User
                globalFlag: String
            }
            extend type Mutation {
                updateUser: User
                namespace: MutationNamespace
            }
            type MutationNamespace @namespaceType {
                updateUser: User
            }
            type User {
                id: ID!
                name: String
                displayName: String
            }
            """.trimIndent()
        )

        val resolver = MockFieldUnbatchedResolverExecutor(
            objectSelectionSet = objectSelectionSet,
            querySelectionSet = querySelectionSet,
            resolverId = typeName + "." + fieldName
        )

        val ctx = FieldResolverExecutorValidationCtx(
            coord = Coordinate(typeName, fieldName),
            executor = resolver,
        )

        val validator = ResolverSelectionSetsAreProperlyTyped(schema)
        validator.validate(ctx)
    }
}
