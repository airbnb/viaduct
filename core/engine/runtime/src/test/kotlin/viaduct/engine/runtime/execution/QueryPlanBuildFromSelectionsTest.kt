@file:OptIn(ExperimentalCoroutinesApi::class)

package viaduct.engine.runtime.execution

import graphql.schema.GraphQLNamedType
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain as shouldContainString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.mocks.createEngineSelectionSet
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.runtime.RequiredSelectionSetRegistry
import viaduct.engine.runtime.execution.ExecutionTestHelpers.runExecutionTest
import viaduct.graphql.utils.ParsedSelections

/**
 * Tests for [QueryPlanFactory.buildFromSelections] which builds a QueryPlan from a EngineSelectionSet
 * for subquery execution.
 *
 * These tests validate:
 * - Basic QueryPlan building from EngineSelectionSet
 * - Error handling for empty EngineSelectionSet values
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
    ): EngineSelectionSet =
        createEngineSelectionSet(
            SelectionsParser.parse(typename, selections),
            schema,
            vars
        )

    private fun mkParameters(query: String = ""): QueryPlan.Parameters =
        QueryPlan.Parameters(
            query = query,
            schema = schema,
            registry = RequiredSelectionSetRegistry.Empty,
        )

    private fun QueryPlan.parentTypeName(): String = (parentType as GraphQLNamedType).name

    @Test
    fun `builds QueryPlan from simple EngineSelectionSet`(): Unit =
        runExecutionTest {
            val rss = mkRss("Query", "user { id name }")
            val params = mkParameters()

            val plan = QueryPlanFactory.Default.buildFromSelections(params, rss)

            assertEquals("Query", plan.parentTypeName())
            // EngineSelectionSet.toSelectionSet() wraps fields in inline fragments by type condition
            plan.selectionSet.selections.shouldHaveSize(1)

            // The selections are wrapped in an InlineFragment
            val inlineFragment = plan.selectionSet.selections.first() as QueryPlan.InlineFragment
            inlineFragment.selectionSet.selections.shouldHaveSize(1)

            val userField = inlineFragment.selectionSet.selections.first() as QueryPlan.Field
            assertEquals("user", userField.resultKey)
        }

    @Test
    fun `builds QueryPlan from EngineSelectionSet with multiple fields`(): Unit =
        runExecutionTest {
            val rss = mkRss("User", "id name email")
            val params = mkParameters()

            val plan = QueryPlanFactory.Default.buildFromSelections(params, rss)

            assertEquals("User", plan.parentTypeName())
            // EngineSelectionSet.toSelectionSet() wraps fields in inline fragments by type condition
            // So we get one InlineFragment containing 3 fields
            plan.selectionSet.selections.shouldHaveSize(1)

            val inlineFragment = plan.selectionSet.selections.first() as QueryPlan.InlineFragment
            inlineFragment.selectionSet.selections.shouldHaveSize(3)
        }

    @Test
    fun `builds QueryPlan from nested selections`(): Unit =
        runExecutionTest {
            val rss = mkRss("Query", "viewer { user { profile { bio } } }")
            val params = mkParameters()

            val plan = QueryPlanFactory.Default.buildFromSelections(params, rss)

            assertEquals("Query", plan.parentTypeName())

            // Navigate through the inline fragment wrapper to find the viewer field
            val inlineFragment = plan.selectionSet.selections.first() as QueryPlan.InlineFragment
            val viewerField = inlineFragment.selectionSet.selections.first() as QueryPlan.Field
            assertEquals("viewer", viewerField.resultKey)
        }

    @Test
    fun `throws IllegalArgumentException for empty EngineSelectionSet`(): Unit =
        runExecutionTest {
            val emptyRss = createEngineSelectionSet(ParsedSelections.empty("Query"), schema, emptyMap())
            val params = mkParameters()

            val exception = assertThrows<IllegalArgumentException> {
                runExecutionTest {
                    QueryPlanFactory.Default.buildFromSelections(params, emptyRss)
                }
            }

            assertNotNull(exception.message)
            exception.message!! shouldContainString "Empty EngineSelectionSet"
            exception.message!! shouldContainString "not supported"
        }

    @Test
    fun `caches QueryPlan for identical selections`(): Unit =
        runExecutionTest {
            val factory = QueryPlanFactory.Cached()
            val rss1 = mkRss("Query", "user { id }")
            val rss2 = mkRss("Query", "user { id }")
            val params = mkParameters()

            val plan1 = factory.buildFromSelections(params, rss1)
            val plan2 = factory.buildFromSelections(params, rss2)

            // Same selection text should produce same cached plan within the same factory instance
            assertSame(plan2, plan1)
        }

    @Test
    fun `produces different QueryPlans for different selections`(): Unit =
        runExecutionTest {
            val rss1 = mkRss("Query", "user { id }")
            val rss2 = mkRss("Query", "user { name }")
            val params = mkParameters()

            val plan1 = QueryPlanFactory.Default.buildFromSelections(params, rss1)
            val plan2 = QueryPlanFactory.Default.buildFromSelections(params, rss2)

            assertNotSame(plan2, plan1)
        }

    @Test
    fun `handles EngineSelectionSet with inline fragments`(): Unit =
        runExecutionTest {
            val rss = mkRss("Item", "id ... on User { name email }")
            val params = mkParameters()

            val plan = QueryPlanFactory.Default.buildFromSelections(params, rss)

            assertEquals("Item", plan.parentTypeName())
            // Should have inline fragments for Item (id) and User (name, email)
            assertEquals(2, plan.selectionSet.selections.size)
        }

    @Test
    fun `handles EngineSelectionSet with variables`(): Unit =
        runExecutionTest {
            // Variables are stored in the RSS context but don't affect QueryPlan structure
            val rss = mkRss("Query", "item(id: \$itemId) { id }", mapOf("itemId" to "123"))
            val params = mkParameters()

            val plan = QueryPlanFactory.Default.buildFromSelections(params, rss)

            assertEquals("Query", plan.parentTypeName())
            plan.selectionSet.selections.shouldHaveSize(1)

            // Navigate through inline fragment to get the item field
            val inlineFragment = plan.selectionSet.selections.first() as QueryPlan.InlineFragment
            val itemField = inlineFragment.selectionSet.selections.first() as QueryPlan.Field
            assertEquals("item", itemField.resultKey)
        }

    @Test
    fun `handles EngineSelectionSet with aliased fields`(): Unit =
        runExecutionTest {
            val rss = mkRss("User", "userId: id userName: name")
            val params = mkParameters()

            val plan = QueryPlanFactory.Default.buildFromSelections(params, rss)

            assertEquals("User", plan.parentTypeName())
            // Wrapped in inline fragment
            plan.selectionSet.selections.shouldHaveSize(1)

            val inlineFragment = plan.selectionSet.selections.first() as QueryPlan.InlineFragment
            val selections = inlineFragment.selectionSet.selections.map { (it as QueryPlan.Field).resultKey }
            selections shouldContain "userId"
            selections shouldContain "userName"
        }

    @Test
    fun `uses different cache keys for different parent types`(): Unit =
        runExecutionTest {
            val factory = QueryPlanFactory.Cached()
            // Same field name but different parent types
            val rssUser = mkRss("User", "id")
            val rssItem = mkRss("Item", "id")
            val params = mkParameters()

            val planUser = factory.buildFromSelections(params, rssUser)
            val planItem = factory.buildFromSelections(params, rssItem)

            assertEquals("User", planUser.parentTypeName())
            assertEquals("Item", planItem.parentTypeName())
            // Different parent types should produce different plans
            assertNotSame(planItem, planUser)
        }

    @Test
    fun `handles EngineSelectionSet with fragment spreads`(): Unit =
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

            val plan = QueryPlanFactory.Default.buildFromSelections(params, rss)

            assertEquals("User", plan.parentTypeName())
            // EngineSelectionSet.toSelectionSet() inlines fragment spreads, so we should
            // see all fields from both the main selection and the spread fragment
            plan.selectionSet.selections.shouldHaveSize(1)

            val inlineFragment = plan.selectionSet.selections.first() as QueryPlan.InlineFragment
            // Should have id, name, email, and profile fields (fragment spread is inlined)
            inlineFragment.selectionSet.selections.shouldHaveSize(4)

            val fieldNames = inlineFragment.selectionSet.selections
                .filterIsInstance<QueryPlan.Field>()
                .map { it.resultKey }
            fieldNames shouldContain "id"
            fieldNames shouldContain "name"
            fieldNames shouldContain "email"
            fieldNames shouldContain "profile"
        }
}
