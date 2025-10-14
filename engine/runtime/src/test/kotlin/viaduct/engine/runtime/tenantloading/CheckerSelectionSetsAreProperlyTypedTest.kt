package viaduct.engine.runtime.tenantloading

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.mocks.MockCheckerExecutor
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.mocks.mkRSS

class CheckerSelectionSetsAreProperlyTypedTest {
    @Test
    fun `valid -- no selection sets`() {
        assertValid("User", emptyMap())
    }

    @Test
    fun `valid -- single selection set with correct type`() {
        val rss = mkCheckerRSS("User", "id name")
        assertValid("User", mapOf("key1" to rss))
    }

    @Test
    fun `valid -- multiple selection sets with correct type`() {
        val rss1 = mkCheckerRSS("User", "id")
        val rss2 = mkCheckerRSS("User", "name email")
        assertValid("User", mapOf("key1" to rss1, "key2" to rss2))
    }

    @Test
    fun `valid -- selection sets with null values`() {
        val rss = mkCheckerRSS("User", "id")
        assertValid("User", mapOf("key1" to rss, "key2" to null))
    }

    @Test
    fun `valid -- single selection set on rooot query`() {
        val rss = mkCheckerRSS("Query", "currentUser")
        assertValid("User", mapOf("key1" to rss))
    }

    @Test
    fun `valid -- multiple selection sets with object type and root query`() {
        val validRss = mkCheckerRSS("User", "id")
        val invalidRss = mkCheckerRSS("Query", "currentUser")
        assertValid("User", mapOf("valid" to validRss, "invalid" to invalidRss))
    }

    @Test
    fun `invalid -- single selection set with wrong type`() {
        val rss = mkCheckerRSS("Post", "title") // Wrong! Should be on either "User" or "Query"
        assertInvalid("User", mapOf("key1" to rss), "Post")
    }

    @Test
    fun `invalid -- all selection sets with wrong types`() {
        val rss1 = mkCheckerRSS("Comment", "body") // Wrong! Should be on either "User" or "Query"
        val rss2 = mkCheckerRSS("Post", "title") // Wrong! Should be on either "User" or "Query"
        assertInvalid("User", mapOf("key1" to rss1, "key2" to rss2), "Comment", "Post")
    }

    @Test
    fun `invalid -- mixed valid and invalid selection sets`() {
        val validRss1 = mkCheckerRSS("User", "id") // Correct
        val validRss2 = mkCheckerRSS("Query", "currentUser") // Correct
        val invalidRss = mkCheckerRSS("Post", "title") // Wrong! Should on either "User" or "Query"
        assertInvalid(
            "User",
            mapOf("valid1" to validRss1, "valid2" to validRss2, "invalid" to invalidRss),
            "Post"
        )
    }

    @Test
    fun `invalid -- multiple different wrong types`() {
        val rss1 = mkCheckerRSS("Query", "currentUser") // Correct
        val rss2 = mkCheckerRSS("Post", "title") // Wrong! Should on either "User" or "Query"
        val rss3 = mkCheckerRSS("Comment", "body") // Wrong! Should on either "User" or "Query"
        assertInvalid(
            "User",
            mapOf("key1" to rss1, "key2" to rss2, "key3" to rss3),
            "Post",
            "Comment"
        )
    }

    /**
     * Helper method to validate checker selection sets and assert no exception is thrown
     */
    private fun assertValid(
        typeName: String,
        requiredSelectionSets: Map<String, RequiredSelectionSet?>
    ) {
        assertDoesNotThrow {
            validateCheckerSelectionSets(typeName, requiredSelectionSets)
        }
    }

    /**
     * Helper method to validate checker selection sets and assert an exception is thrown
     * containing the specified invalid type names
     */
    private fun assertInvalid(
        typeName: String,
        requiredSelectionSets: Map<String, RequiredSelectionSet?>,
        vararg expectedInvalidTypeNames: String
    ) {
        val exception = assertThrows<Exception> {
            validateCheckerSelectionSets(typeName, requiredSelectionSets)
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
     * Creates a CheckerExecutorValidationCtx and validates it using CheckerSelectionSetsAreProperlyTyped
     */
    private fun validateCheckerSelectionSets(
        typeName: String,
        requiredSelectionSets: Map<String, RequiredSelectionSet?>
    ) {
        val schema = MockSchema.mk(
            """
            extend type Query {
                empty: Int
                currentUser: User
                globalFlag: String
            }
            type User {
                id: ID!
                name: String
                email: String
            }
            type Post {
                title: String
                author: User
            }
            type Comment {
                body: String
                post: Post
            }
            """.trimIndent()
        )

        val checker = MockCheckerExecutor(requiredSelectionSets = requiredSelectionSets)

        val ctx = CheckerExecutorValidationCtx(
            typeName = typeName,
            executor = checker
        )

        val validator = CheckerSelectionSetsAreProperlyTyped(schema)
        validator.validate(ctx)
    }

    private fun mkCheckerRSS(
        typeName: String,
        selectionString: String
    ): RequiredSelectionSet = mkRSS(typeName, selectionString, forChecker = true)
}
