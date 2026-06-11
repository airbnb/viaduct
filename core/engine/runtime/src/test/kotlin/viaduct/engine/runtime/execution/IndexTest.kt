package viaduct.engine.runtime.execution

import graphql.language.Field as GJField
import graphql.language.FragmentDefinition as GJFragmentDefinition
import graphql.language.SelectionSet as GJSelectionSet
import graphql.language.TypeName as GJTypeName
import graphql.schema.GraphQLObjectType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import viaduct.arbitrary.graphql.asDocument
import viaduct.arbitrary.graphql.asSchema
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.mocks.MockRequiredSelectionSetRegistry
import viaduct.engine.api.mocks.createRSS
import viaduct.engine.runtime.QueryPlanExecutionCondition.Companion.ALWAYS_EXECUTE
import viaduct.engine.runtime.execution.ExecutionTestHelpers.runExecutionTest
import viaduct.engine.runtime.execution.constraints.Constraints

class IndexTest {
    private val parentType: GraphQLObjectType = GraphQLObjectType.newObject().name("Query").build()
    private val emptyAst: GJSelectionSet = GJSelectionSet.newSelectionSet().build()

    private fun queryPlan(
        childPlans: List<QueryPlan> = emptyList(),
        selectionSet: QueryPlan.SelectionSet = QueryPlan.SelectionSet.empty,
        fragments: QueryPlan.Fragments = QueryPlan.Fragments.empty,
        baseIndex: QueryPlanIndex = indexOf(childPlans),
        requiredSelectionSetId: viaduct.engine.api.RequiredSelectionSet.Id? = null,
    ): QueryPlan =
        QueryPlan(
            selectionSet = selectionSet,
            fragments = fragments,
            variablesResolvers = emptyList(),
            parentType = parentType,
            childPlanIds = childPlans.map { requireNotNull(it.requiredSelectionSetId) },
            baseIndex = baseIndex,
            astSelectionSet = emptyAst,
            attribution = ExecutionAttribution.DEFAULT,
            executionCondition = ALWAYS_EXECUTE,
            variableDefinitions = emptyList(),
            requiredSelectionSetId = requiredSelectionSetId,
        )

    private companion object {
        fun indexOf(plans: Iterable<QueryPlan>): QueryPlanIndex = plans.fold(QueryPlanIndex.empty()) { index, plan -> plan.index.merge(index) }

        fun indexOf(vararg plans: QueryPlan): QueryPlanIndex = indexOf(plans.asIterable())
    }

    @Nested
    inner class GenericIndexTests {
        @Test
        fun `empty index returns null for unknown keys`() {
            val index = Index.empty<String, Int>()

            assertNull(index.find("missing"))
            assertNull(index["missing"])
        }

        @Test
        fun `builder indexes values by key`() {
            val index = Index.Builder<String, Int>()
                .add("one", 1)
                .add("two", 2)
                .build()

            assertEquals(1, index.find("one"))
            assertEquals(2, index.find("two"))
            assertNull(index.find("three"))
        }

        @Test
        fun `operator get returns the same value as find`() {
            val index = Index.Builder<String, Int>()
                .add("key", 1)
                .build()

            assertEquals(index.find("key"), index["key"])
        }

        @Test
        fun `merge treats empty index as identity`() {
            val index = Index.Builder<String, Int>()
                .add("key", 1)
                .build()

            assertSame(index, Index.empty<String, Int>().merge(index))
            assertSame(index, index.merge(Index.empty()))
        }

        @Test
        fun `merge prefers overrides for duplicate keys`() {
            val base = Index.Builder<String, Int>()
                .add("key", 1)
                .build()
            val overrides = Index.Builder<String, Int>()
                .add("key", 2)
                .build()

            val index = base.merge(overrides)

            assertEquals(2, index["key"])
        }
    }

    @Nested
    inner class QueryPlanIndexTests {
        @Test
        fun `subtree index indexes plans by RequiredSelectionSet Id`() {
            val rss = createRSS("Query", "foo")
            val childPlan = queryPlan(requiredSelectionSetId = rss.id)
            val rootPlan = queryPlan(childPlans = listOf(childPlan))

            val index = rootPlan.index

            assertSame(childPlan, index.find(rss.id))
        }

        @Test
        fun `subtree index indexes nested child plans`() {
            val rss1 = createRSS("Query", "foo")
            val rss2 = createRSS("Query", "bar")
            val grandchild = queryPlan(requiredSelectionSetId = rss2.id)
            val child = queryPlan(requiredSelectionSetId = rss1.id, childPlans = listOf(grandchild))
            val rootPlan = queryPlan(childPlans = listOf(child))

            val index = rootPlan.index

            assertSame(child, index.find(rss1.id))
            assertSame(grandchild, index.find(rss2.id))
        }

        @Test
        fun `subtree index prefers the nearest plan for duplicate RequiredSelectionSet Ids`() {
            val rss = createRSS("Query", "foo")
            val descendantPlan = queryPlan(requiredSelectionSetId = rss.id)
            val directChildPlan = queryPlan(requiredSelectionSetId = rss.id, childPlans = listOf(descendantPlan))
            val rootPlan = queryPlan(childPlans = listOf(directChildPlan))

            val index = rootPlan.index

            assertSame(directChildPlan, index.find(rss.id))
        }

        @Test
        fun `subtree index prefers self for duplicate RequiredSelectionSet Ids`() {
            val rss = createRSS("Query", "foo")
            val childPlan = queryPlan(requiredSelectionSetId = rss.id)
            val rootPlan = queryPlan(requiredSelectionSetId = rss.id, childPlans = listOf(childPlan))

            val index = rootPlan.index

            assertSame(rootPlan, index.find(rss.id))
        }

        @Test
        fun `QueryPlan subtree index is scoped to the receiver plan`() {
            val rss = createRSS("Query", "foo")
            val descendantPlan = queryPlan(requiredSelectionSetId = rss.id)
            val directChildPlan = queryPlan(requiredSelectionSetId = rss.id, childPlans = listOf(descendantPlan))
            val rootPlan = queryPlan(childPlans = listOf(directChildPlan))

            assertSame(directChildPlan, rootPlan.index.find(rss.id))
            assertSame(descendantPlan, descendantPlan.index.find(rss.id))
        }

        @Test
        fun `subtree index indexes field child plans inside named fragments`() {
            val registry = MockRequiredSelectionSetRegistry.builder()
                .fieldResolverEntry("Query" to "x", "y")
                .build()
            val rss = registry.getFieldResolverRequiredSelectionSets("Query", "x").single()
            val document = """
                {
                  ...QueryFragment
                }

                fragment QueryFragment on Query {
                  x
                }
            """.trimIndent()
            val plan = runExecutionTest {
                QueryPlanFactory.Default.build(
                    QueryPlan.Parameters(
                        query = document,
                        schema = ViaductSchema("type Query { x: Int y: Int }".asSchema),
                        registry = registry,
                    ),
                    document.asDocument,
                )
            }

            val index = plan.index

            assertNotNull(index.find(rss.id))
        }

        @Test
        fun `subtree index does not force root field type child plan indexing`() {
            val rss = createRSS("Query", "foo")
            val fieldTypeChildPlan = queryPlan(requiredSelectionSetId = rss.id)
            var evaluatedFieldTypeChildPlans = false
            val rootPlan = queryPlan(
                selectionSet = QueryPlan.SelectionSet(
                    QueryPlan.Field(
                        resultKey = "poly",
                        constraints = Constraints.Unconstrained,
                        field = GJField("poly"),
                        selectionSet = null,
                        childPlans = emptyList(),
                        fieldTypeChildPlans = mapOf(
                            parentType to lazy {
                                evaluatedFieldTypeChildPlans = true
                                listOf(fieldTypeChildPlan)
                            },
                        ),
                    ),
                ),
            )

            val index = rootPlan.index

            assertFalse(evaluatedFieldTypeChildPlans)
            assertNull(index.find(rss.id))
        }

        @Test
        fun `subtree index indexes fragment child plans without forcing field type child plans`() {
            val fragmentRss = createRSS("Query", "fragment")
            val fieldTypeRss = createRSS("Query", "fieldType")
            val fragmentChildPlan = queryPlan(requiredSelectionSetId = fragmentRss.id)
            val fieldTypeChildPlan = queryPlan(requiredSelectionSetId = fieldTypeRss.id)
            val fragmentIndex = indexOf(fragmentChildPlan)
            var evaluatedFieldTypeChildPlans = false
            val fragmentName = "QueryFragment"
            val rootPlan = queryPlan(
                selectionSet = QueryPlan.SelectionSet(
                    QueryPlan.FragmentSpread(fragmentName, Constraints.Unconstrained),
                ),
                fragments = QueryPlan.Fragments(
                    mapOf(
                        fragmentName to QueryPlan.FragmentDefinition(
                            QueryPlan.SelectionSet(
                                QueryPlan.Field(
                                    resultKey = "poly",
                                    constraints = Constraints.Unconstrained,
                                    field = GJField("poly"),
                                    selectionSet = null,
                                    childPlans = listOf(FieldChildPlan(fragmentChildPlan, "Query" to "poly")),
                                    fieldTypeChildPlans = mapOf(
                                        parentType to lazy {
                                            evaluatedFieldTypeChildPlans = true
                                            listOf(fieldTypeChildPlan)
                                        },
                                    ),
                                ),
                            ),
                            GJFragmentDefinition.newFragmentDefinition()
                                .name(fragmentName)
                                .typeCondition(GJTypeName("Query"))
                                .selectionSet(emptyAst)
                                .build(),
                            childPlanIds = emptyList(),
                            index = fragmentIndex,
                        ),
                    ),
                ),
                baseIndex = fragmentIndex,
            )

            val index = rootPlan.index

            assertSame(fragmentChildPlan, index.find(fragmentRss.id))
            assertFalse(evaluatedFieldTypeChildPlans)
            assertNull(index.find(fieldTypeRss.id))
        }

        @Test
        fun `query plan index merge indexes runtime forced plans and materialized descendants without forcing nested field type child plans`() {
            val baseRss = createRSS("Query", "base")
            val runtimeRss = createRSS("Query", "runtime")
            val childRss = createRSS("Query", "child")
            val fieldChildRss = createRSS("Query", "fieldChild")
            val nestedFieldTypeRss = createRSS("Query", "nestedFieldType")
            val overriddenRuntimePlan = queryPlan(requiredSelectionSetId = runtimeRss.id)
            val basePlan = queryPlan(requiredSelectionSetId = baseRss.id, childPlans = listOf(overriddenRuntimePlan))
            val materializedChildPlan = queryPlan(requiredSelectionSetId = childRss.id)
            val fieldMaterializedChildPlan = queryPlan(requiredSelectionSetId = fieldChildRss.id)
            val nestedFieldTypeChildPlan = queryPlan(requiredSelectionSetId = nestedFieldTypeRss.id)
            var evaluatedNestedFieldTypeChildPlans = false
            val runtimePlan = queryPlan(
                requiredSelectionSetId = runtimeRss.id,
                childPlans = listOf(materializedChildPlan),
                baseIndex = indexOf(materializedChildPlan, fieldMaterializedChildPlan),
                selectionSet = QueryPlan.SelectionSet(
                    QueryPlan.Field(
                        resultKey = "poly",
                        constraints = Constraints.Unconstrained,
                        field = GJField("poly"),
                        selectionSet = null,
                        childPlans = listOf(FieldChildPlan(fieldMaterializedChildPlan, "Query" to "poly")),
                        fieldTypeChildPlans = mapOf(
                            parentType to lazy {
                                evaluatedNestedFieldTypeChildPlans = true
                                listOf(nestedFieldTypeChildPlan)
                            },
                        ),
                    ),
                ),
            )
            val baseIndex = basePlan.index
            val runtimeIndex = runtimePlan.index

            val index = baseIndex.merge(runtimeIndex)

            assertSame(basePlan, index.find(baseRss.id))
            assertSame(runtimePlan, index.find(runtimeRss.id))
            assertSame(materializedChildPlan, index.find(childRss.id))
            assertSame(fieldMaterializedChildPlan, index.find(fieldChildRss.id))
            assertFalse(evaluatedNestedFieldTypeChildPlans)
            assertNull(index.find(nestedFieldTypeRss.id))
        }

        @Test
        fun `empty index returns null for every RequiredSelectionSet Id`() {
            val rss = createRSS("Query", "foo")
            val index: QueryPlanIndex = QueryPlanIndex.empty()

            assertNull(index.find(rss.id))
        }

        @Test
        fun `merge treats empty index as identity`() {
            val rss = createRSS("Query", "foo")
            val plan = queryPlan(requiredSelectionSetId = rss.id)
            val emptyIndex: QueryPlanIndex = QueryPlanIndex.empty()

            assertSame(plan.index, emptyIndex.merge(plan.index))
            assertSame(plan.index, plan.index.merge(emptyIndex))
        }

        @Test
        fun `merge prefers overlay index when base and overlay contain same RequiredSelectionSet Id`() {
            val rss = createRSS("Query", "foo")
            val basePlan = queryPlan(requiredSelectionSetId = rss.id)
            val overlayPlan = queryPlan(requiredSelectionSetId = rss.id)

            val index = basePlan.index.merge(overlayPlan.index)

            assertSame(overlayPlan, index.find(rss.id))
        }

        @Test
        fun `flattenIndex indexes all plans`() {
            val fooRss = createRSS("Query", "foo")
            val barRss = createRSS("Query", "bar")
            val fooPlan = queryPlan(requiredSelectionSetId = fooRss.id)
            val barPlan = queryPlan(requiredSelectionSetId = barRss.id)

            val index = listOf(fooPlan, barPlan).flattenIndex()

            assertSame(fooPlan, index.find(fooRss.id))
            assertSame(barPlan, index.find(barRss.id))
        }

        @Test
        fun `subtree index returns null for unknown id`() {
            val rss = createRSS("Query", "foo")
            val unknownRss = createRSS("Query", "unknown")
            val childPlan = queryPlan(requiredSelectionSetId = rss.id)
            val rootPlan = queryPlan(childPlans = listOf(childPlan))

            val index = rootPlan.index

            assertNull(index.find(unknownRss.id))
        }
    }
}
