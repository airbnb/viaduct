package viaduct.engine.runtime.execution

import graphql.language.Field as GJField
import graphql.language.FragmentDefinition as GJFragmentDefinition
import graphql.language.SelectionSet as GJSelectionSet
import graphql.language.TypeName as GJTypeName
import graphql.schema.GraphQLObjectType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
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

class QueryPlanIndexTest {
    private val parentType: GraphQLObjectType = GraphQLObjectType.newObject().name("Query").build()
    private val emptyAst: GJSelectionSet = GJSelectionSet.newSelectionSet().build()

    private fun queryPlan(
        childPlans: List<QueryPlan> = emptyList(),
        selectionSet: QueryPlan.SelectionSet = QueryPlan.SelectionSet.empty,
        fragments: QueryPlan.Fragments = QueryPlan.Fragments.empty,
        requiredSelectionSetId: viaduct.engine.api.RequiredSelectionSet.Id? = null,
    ): QueryPlan =
        QueryPlan(
            selectionSet = selectionSet,
            fragments = fragments,
            variablesResolvers = emptyList(),
            parentType = parentType,
            childPlans = childPlans,
            astSelectionSet = emptyAst,
            attribution = ExecutionAttribution.DEFAULT,
            executionCondition = ALWAYS_EXECUTE,
            variableDefinitions = emptyList(),
            requiredSelectionSetId = requiredSelectionSetId,
        )

    @Nested
    inner class FactoryTests {
        @Test
        fun `Default create indexes plans by RequiredSelectionSet Id`() {
            val rss = createRSS("Query", "foo")
            val childPlan = queryPlan(requiredSelectionSetId = rss.id)
            val rootPlan = queryPlan(childPlans = listOf(childPlan))

            val index = QueryPlanIndex.Factory.Default.create(rootPlan)

            assertSame(childPlan, index.find(rss.id))
        }

        @Test
        fun `Default create indexes nested child plans`() {
            val rss1 = createRSS("Query", "foo")
            val rss2 = createRSS("Query", "bar")
            val grandchild = queryPlan(requiredSelectionSetId = rss2.id)
            val child = queryPlan(requiredSelectionSetId = rss1.id, childPlans = listOf(grandchild))
            val rootPlan = queryPlan(childPlans = listOf(child))

            val index = QueryPlanIndex.Factory.Default.create(rootPlan)

            assertSame(child, index.find(rss1.id))
            assertSame(grandchild, index.find(rss2.id))
        }

        @Test
        fun `Default create indexes field child plans inside named fragments`() {
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
                        executeAccessChecksInModstrat = false,
                    ),
                    document.asDocument,
                )
            }

            val index = QueryPlanIndex.Factory.Default.create(plan)

            assertNotNull(index.find(rss.id))
        }

        @Test
        fun `Default create does not force root field type child plan indexing`() {
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

            val index = QueryPlanIndex.Factory.Default.create(rootPlan)

            assertFalse(evaluatedFieldTypeChildPlans)
            assertNull(index.find(rss.id))
        }

        @Test
        fun `Default create indexes fragment child plans without forcing field type child plans`() {
            val fragmentRss = createRSS("Query", "fragment")
            val fieldTypeRss = createRSS("Query", "fieldType")
            val fragmentChildPlan = queryPlan(requiredSelectionSetId = fragmentRss.id)
            val fieldTypeChildPlan = queryPlan(requiredSelectionSetId = fieldTypeRss.id)
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
                                    childPlans = listOf(fragmentChildPlan),
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
                            childPlans = emptyList(),
                        ),
                    ),
                ),
            )

            val index = QueryPlanIndex.Factory.Default.create(rootPlan)

            assertSame(fragmentChildPlan, index.find(fragmentRss.id))
            assertFalse(evaluatedFieldTypeChildPlans)
            assertNull(index.find(fieldTypeRss.id))
        }

        @Test
        fun `QueryPlanIndex merge indexes runtime forced plans and materialized descendants without forcing nested field type child plans`() {
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
                selectionSet = QueryPlan.SelectionSet(
                    QueryPlan.Field(
                        resultKey = "poly",
                        constraints = Constraints.Unconstrained,
                        field = GJField("poly"),
                        selectionSet = null,
                        childPlans = listOf(fieldMaterializedChildPlan),
                        fieldTypeChildPlans = mapOf(
                            parentType to lazy {
                                evaluatedNestedFieldTypeChildPlans = true
                                listOf(nestedFieldTypeChildPlan)
                            },
                        ),
                    ),
                ),
            )
            val baseIndex = QueryPlanIndex.Factory.Default.create(basePlan)
            val runtimeIndex = QueryPlanIndex.Factory.Default.create(runtimePlan)

            val index = baseIndex.merge(runtimeIndex)

            assertSame(basePlan, index.find(baseRss.id))
            assertSame(runtimePlan, index.find(runtimeRss.id))
            assertSame(materializedChildPlan, index.find(childRss.id))
            assertSame(fieldMaterializedChildPlan, index.find(fieldChildRss.id))
            assertFalse(evaluatedNestedFieldTypeChildPlans)
            assertNull(index.find(nestedFieldTypeRss.id))
        }

        @Test
        fun `Default create returns null for unknown id`() {
            val rss = createRSS("Query", "foo")
            val unknownRss = createRSS("Query", "unknown")
            val childPlan = queryPlan(requiredSelectionSetId = rss.id)
            val rootPlan = queryPlan(childPlans = listOf(childPlan))

            val index = QueryPlanIndex.Factory.Default.create(rootPlan)

            assertNull(index.find(unknownRss.id))
        }

        @Test
        fun `Cached create returns the same index instance for the same plan`() {
            val rootPlan = queryPlan()
            val factory = QueryPlanIndex.Factory.Cached()

            val index1 = factory.create(rootPlan)
            val index2 = factory.create(rootPlan)

            assertSame(index1, index2)
        }

        @Test
        fun `Cached create returns different instances for different plans`() {
            val factory = QueryPlanIndex.Factory.Cached()
            val plan1 = queryPlan()
            val plan2 = queryPlan()

            val index1 = factory.create(plan1)
            val index2 = factory.create(plan2)

            assertNotSame(index1, index2)
        }
    }
}
