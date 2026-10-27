package viaduct.engine.runtime.tenantloading

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.mocks.MockCheckerExecutor
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.mocks.createRSS

class CheckerSelectionSetsDoNotSelectParentFieldsTest {
    private val schema = MockSchema.mk(
        """
        extend type Query { currentUser: User }
        type User { id: ID, name: String, company: Company, parent: Company @parent }
        type Company { name: String, owner: User }
        """.trimIndent()
    )

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        textBlock = """
        User  | id parent { name }                                                 | User.parent
        User  | company { owner { parent { name } } }                              | User.parent
        User  | ... on User { parent { name } }                                    | User.parent
        User  | id @include(if: false) parent @skip(if: true) { name }             | User.parent
        User  | fragment Main on User { ...F } fragment F on User { parent { name } } | User.parent
        Query | currentUser { parent { name } }                                    | User.parent"""
    )
    fun `rejects @parent fields anywhere in a checker selection set`(
        rssTypeName: String,
        selections: String,
        expectedCoordinate: String,
    ) {
        val exception = assertThrows<Exception> {
            validate(mapOf("rss" to createRSS(rssTypeName, selections, forChecker = true)))
        }
        assertTrue(exception.message!!.contains(expectedCoordinate), exception.message)
    }

    @Test
    fun `accepts checker selection sets without @parent fields`() {
        assertDoesNotThrow {
            validate(
                mapOf(
                    "object" to createRSS("User", "id company { name owner { id } }", forChecker = true),
                    "query" to createRSS("Query", "currentUser { name }", forChecker = true),
                    "absent" to null,
                )
            )
        }
    }

    private fun validate(requiredSelectionSets: Map<String, RequiredSelectionSet?>) =
        CheckerSelectionSetsDoNotSelectParentFields(schema).validate(
            CheckerExecutorValidationCtx("User", MockCheckerExecutor(requiredSelectionSets = requiredSelectionSets))
        )
}
