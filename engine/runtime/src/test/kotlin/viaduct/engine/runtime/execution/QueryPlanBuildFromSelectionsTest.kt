@file:OptIn(ExperimentalCoroutinesApi::class)

package viaduct.engine.runtime.execution

import graphql.schema.GraphQLNamedType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo
import strikt.assertions.isNotNull
import strikt.assertions.isNotSameInstanceAs
import strikt.assertions.isSameInstanceAs
import viaduct.engine.api.RawSelectionSet
import viaduct.engine.api.RequiredSelectionSetRegistry
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.runtime.execution.ExecutionTestHelpers.runExecutionTest
import viaduct.engine.runtime.select.RawSelectionSetImpl

/**
 * Tests for [QueryPlan.buildFromSelections] which builds a QueryPlan from a RawSelectionSet
 * for subquery execution.
 *
 * These tests validate:
 * - Basic QueryPlan building from RawSelectionSet
 * - Error handling for RawSelectionSet.Empty
 * - Caching behavior for identical selections
 * - Proper extraction of parent type and fragments
 */
class QueryPlanBuildFromSelectionsTest {
    private val schema = MockSchema.mk(
        """
        extend type Query {
            user: User
            viewer: Viewer
            item(id: ID!): Item
        }

        interface Item {
            id: ID!
        }

        type User implements Item {
            id: ID!
            name: String
            email: String
            profile: Profile
        }

        type Viewer {
            user: User
            preferences: Preferences
        }

        type Profile {
            bio: String
            avatar: String
        }

        type Preferences {
            theme: String
        }
        """
    )

    private fun mkRss(
        typename: String,
        selections: String,
        vars: Map<String, Any?> = emptyMap()
    ): RawSelectionSetImpl =
        RawSelectionSetImpl.create(
            SelectionsParser.parse(typename, selections),
            vars,
            schema
        )

    private fun mkParameters(query: String = ""): QueryPlan.Parameters =
        QueryPlan.Parameters(
            query = query,
            schema = schema,
            registry = RequiredSelectionSetRegistry.Empty,
            executeAccessChecksInModstrat = false
        )

    private fun QueryPlan.parentTypeName(): String = (parentType as GraphQLNamedType).name

    @Test
    fun `builds QueryPlan from simple RawSelectionSet`(): Unit =
        runExecutionTest {
            val rss = mkRss("Query", "user { id name }")
            val params = mkParameters()

            val plan = QueryPlan.buildFromSelections(params, rss)

            expectThat(plan.parentTypeName()).isEqualTo("Query")
            // RawSelectionSet.toSelectionSet() wraps fields in inline fragments by type condition
            expectThat(plan.selectionSet.selections).hasSize(1)

            // The selections are wrapped in an InlineFragment
            val inlineFragment = plan.selectionSet.selections.first() as QueryPlan.InlineFragment
            expectThat(inlineFragment.selectionSet.selections).hasSize(1)

            val userField = inlineFragment.selectionSet.selections.first() as QueryPlan.Field
            expectThat(userField.resultKey).isEqualTo("user")
        }

    @Test
    fun `builds QueryPlan from RawSelectionSet with multiple fields`(): Unit =
        runExecutionTest {
            val rss = mkRss("User", "id name email")
            val params = mkParameters()

            val plan = QueryPlan.buildFromSelections(params, rss)

            expectThat(plan.parentTypeName()).isEqualTo("User")
            // RawSelectionSet.toSelectionSet() wraps fields in inline fragments by type condition
            // So we get one InlineFragment containing 3 fields
            expectThat(plan.selectionSet.selections).hasSize(1)

            val inlineFragment = plan.selectionSet.selections.first() as QueryPlan.InlineFragment
            expectThat(inlineFragment.selectionSet.selections).hasSize(3)
        }

    @Test
    fun `builds QueryPlan from nested selections`(): Unit =
        runExecutionTest {
            val rss = mkRss("Query", "viewer { user { profile { bio } } }")
            val params = mkParameters()

            val plan = QueryPlan.buildFromSelections(params, rss)

            expectThat(plan.parentTypeName()).isEqualTo("Query")

            // Navigate through the inline fragment wrapper to find the viewer field
            val inlineFragment = plan.selectionSet.selections.first() as QueryPlan.InlineFragment
            val viewerField = inlineFragment.selectionSet.selections.first() as QueryPlan.Field
            expectThat(viewerField.resultKey).isEqualTo("viewer")
        }

    @Test
    fun `throws IllegalArgumentException for RawSelectionSet Empty`(): Unit =
        runExecutionTest {
            val emptyRss = RawSelectionSet.empty("Query")
            val params = mkParameters()

            val exception = assertThrows<IllegalArgumentException> {
                runExecutionTest {
                    QueryPlan.buildFromSelections(params, emptyRss)
                }
            }

            expectThat(exception.message).isNotNull().and {
                contains("RawSelectionSet.Empty")
                contains("not supported")
            }
        }

    @Test
    fun `caches QueryPlan for identical selections`(): Unit =
        runExecutionTest {
            QueryPlan.resetCache()

            val rss1 = mkRss("Query", "user { id }")
            val rss2 = mkRss("Query", "user { id }")
            val params = mkParameters()

            val plan1 = QueryPlan.buildFromSelections(params, rss1)
            val plan2 = QueryPlan.buildFromSelections(params, rss2)

            // Same selection text should produce same cached plan
            expectThat(plan1).isSameInstanceAs(plan2)
        }

    @Test
    fun `produces different QueryPlans for different selections`(): Unit =
        runExecutionTest {
            QueryPlan.resetCache()

            val rss1 = mkRss("Query", "user { id }")
            val rss2 = mkRss("Query", "user { name }")
            val params = mkParameters()

            val plan1 = QueryPlan.buildFromSelections(params, rss1)
            val plan2 = QueryPlan.buildFromSelections(params, rss2)

            expectThat(plan1).isNotSameInstanceAs(plan2)
        }

    @Test
    fun `handles RawSelectionSet with inline fragments`(): Unit =
        runExecutionTest {
            val rss = mkRss("Item", "id ... on User { name email }")
            val params = mkParameters()

            val plan = QueryPlan.buildFromSelections(params, rss)

            expectThat(plan.parentTypeName()).isEqualTo("Item")
            // Should have inline fragments for Item (id) and User (name, email)
            expectThat(plan.selectionSet.selections.size).isEqualTo(2)
        }

    @Test
    fun `handles RawSelectionSet with variables`(): Unit =
        runExecutionTest {
            // Variables are stored in the RSS context but don't affect QueryPlan structure
            val rss = mkRss("Query", "item(id: \$itemId) { id }", mapOf("itemId" to "123"))
            val params = mkParameters()

            val plan = QueryPlan.buildFromSelections(params, rss)

            expectThat(plan.parentTypeName()).isEqualTo("Query")
            expectThat(plan.selectionSet.selections).hasSize(1)

            // Navigate through inline fragment to get the item field
            val inlineFragment = plan.selectionSet.selections.first() as QueryPlan.InlineFragment
            val itemField = inlineFragment.selectionSet.selections.first() as QueryPlan.Field
            expectThat(itemField.resultKey).isEqualTo("item")
        }

    @Test
    fun `handles RawSelectionSet with aliased fields`(): Unit =
        runExecutionTest {
            val rss = mkRss("User", "userId: id userName: name")
            val params = mkParameters()

            val plan = QueryPlan.buildFromSelections(params, rss)

            expectThat(plan.parentTypeName()).isEqualTo("User")
            // Wrapped in inline fragment
            expectThat(plan.selectionSet.selections).hasSize(1)

            val inlineFragment = plan.selectionSet.selections.first() as QueryPlan.InlineFragment
            val selections = inlineFragment.selectionSet.selections.map { (it as QueryPlan.Field).resultKey }
            expectThat(selections).contains("userId")
            expectThat(selections).contains("userName")
        }

    @Test
    fun `uses different cache keys for different parent types`(): Unit =
        runExecutionTest {
            QueryPlan.resetCache()

            // Same field name but different parent types
            val rssUser = mkRss("User", "id")
            val rssItem = mkRss("Item", "id")
            val params = mkParameters()

            val planUser = QueryPlan.buildFromSelections(params, rssUser)
            val planItem = QueryPlan.buildFromSelections(params, rssItem)

            expectThat(planUser.parentTypeName()).isEqualTo("User")
            expectThat(planItem.parentTypeName()).isEqualTo("Item")
            // Different parent types should produce different plans
            expectThat(planUser).isNotSameInstanceAs(planItem)
        }

    @Test
    fun `handles RawSelectionSet with fragment spreads`(): Unit =
        runExecutionTest {
            // Fragment spreads are inlined by toSelectionSet(), so the QueryPlan
            // should be built correctly without needing fragment definitions
            val rss = mkRss(
                "User",
                """
                fragment UserFields on User { name email }
                fragment Main on User { id ...UserFields profile { bio } }
                """
            )
            val params = mkParameters()

            val plan = QueryPlan.buildFromSelections(params, rss)

            expectThat(plan.parentTypeName()).isEqualTo("User")
            // RawSelectionSet.toSelectionSet() inlines fragment spreads, so we should
            // see all fields from both the main selection and the spread fragment
            expectThat(plan.selectionSet.selections).hasSize(1)

            val inlineFragment = plan.selectionSet.selections.first() as QueryPlan.InlineFragment
            // Should have id, name, email, and profile fields (fragment spread is inlined)
            expectThat(inlineFragment.selectionSet.selections).hasSize(4)

            val fieldNames = inlineFragment.selectionSet.selections
                .filterIsInstance<QueryPlan.Field>()
                .map { it.resultKey }
            expectThat(fieldNames).contains("id")
            expectThat(fieldNames).contains("name")
            expectThat(fieldNames).contains("email")
            expectThat(fieldNames).contains("profile")
        }
}
