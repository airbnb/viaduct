package viaduct.engine.runtime.execution

import graphql.execution.CoercedVariables
import graphql.execution.MergedField
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.hasSize
import strikt.assertions.isEmpty
import strikt.assertions.map
import viaduct.arbitrary.graphql.asSchema
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.mocks.MockRequiredSelectionSetRegistry
import viaduct.engine.runtime.execution.QueryPlan.CollectedField
import viaduct.engine.runtime.execution.QueryPlan.Field
import viaduct.engine.runtime.execution.QueryPlan.InlineFragment
import viaduct.engine.runtime.execution.QueryPlan.SelectionSet

class CollectFieldsTest {
    private val emptyVars = CoercedVariables.emptyVariables()
    private val trueVars = CoercedVariables.of(mapOf("var" to true))

    @Test
    fun `shallowStrictCollect - single field`() {
        val schema = "type Query { x:Int }".asSchema
        val plan = buildPlan("{x}", ViaductSchema(schema))
        val xField = plan.selectionSet.selections.first() as Field

        val collected = CollectFields.shallowStrictCollect(
            schema,
            plan.selectionSet,
            emptyVars,
            schema.queryType,
            plan.fragments
        )

        expectThat(collected) {
            checkEquals(
                SelectionSet(
                    listOf(
                        CollectedField(
                            "x",
                            null,
                            MergedField.newMergedField().addField(xField.field).build(),
                            emptyList(),
                            emptyMap()
                        )
                    )
                )
            )
        }
    }

    @Test
    fun `shallowStrictCollect -- single skipped field`() {
        val schema = "type Query { x:Int }".asSchema
        val plan = buildPlan("{x @skip(if:\$var) }", ViaductSchema(schema))

        val collected = CollectFields.shallowStrictCollect(
            schema,
            plan.selectionSet,
            trueVars,
            schema.queryType,
            plan.fragments
        )

        expectThat(collected) {
            checkEquals(
                SelectionSet(emptyList())
            )
        }
    }

    @Test
    fun `shallowStrictCollect -- mergeable fields`() {
        val schema = "type Query { x:Int }".asSchema
        val plan = buildPlan("{x x}", ViaductSchema(schema))
        val x0 = plan.selectionSet.selections[0] as Field
        val x1 = plan.selectionSet.selections[1] as Field

        val collected = CollectFields.shallowStrictCollect(
            schema,
            plan.selectionSet,
            emptyVars,
            schema.queryType,
            plan.fragments
        )

        expectThat(collected) {
            checkEquals(
                SelectionSet(
                    listOf(
                        CollectedField(
                            "x",
                            null,
                            MergedField.newMergedField()
                                .addField(x0.field)
                                .addField(x1.field)
                                .build(),
                            emptyList(),
                            emptyMap()
                        )
                    )
                )
            )
        }
    }

    @Test
    fun `shallowStrictCollect --  inline fragment`() {
        val schema = "type Query { x:Int }".asSchema
        val plan = buildPlan("{ ... {x}}", ViaductSchema(schema))
        val xField = (plan.selectionSet.selections.first() as InlineFragment)
            .selectionSet.selections.first() as Field

        val collected = CollectFields.shallowStrictCollect(
            schema,
            plan.selectionSet,
            emptyVars,
            schema.queryType,
            plan.fragments
        )

        expectThat(collected) {
            checkEquals(
                SelectionSet(
                    listOf(
                        CollectedField(
                            "x",
                            null,
                            MergedField.newMergedField().addField(xField.field).build(),
                            emptyList(),
                            emptyMap()
                        )
                    )
                )
            )
        }
    }

    @Test
    fun `shallowStrictCollect --  fragment spread`() {
        val schema = "type Query { x:Int }".asSchema
        val plan = buildPlan(
            """
                { ...F, ...F }
                fragment F on Query { x }
            """.trimIndent(),
            ViaductSchema(schema)
        )
        val xField = plan.fragments["F"]!!.selectionSet.selections.first() as Field

        val collected = CollectFields.shallowStrictCollect(
            schema,
            plan.selectionSet,
            emptyVars,
            schema.queryType,
            plan.fragments
        )

        expectThat(collected) {
            checkEquals(
                SelectionSet(
                    listOf(
                        CollectedField(
                            "x",
                            null,
                            MergedField.newMergedField().addField(xField.field).build(),
                            emptyList(),
                            emptyMap()
                        )
                    )
                )
            )
        }
    }

    @Test
    fun `shallowStrictCollect filters child plans to constrained type and root types`() {
        val schema = """
            type Query {
                entity: Entity
            }

            interface Entity {
                id: ID!
                restricted: String
            }

            type User implements Entity {
                id: ID!
                restricted: String
            }

            type Admin implements Entity {
                id: ID!
                restricted: String
            }
        """.asSchema

        val userType = schema.getObjectType("User")
        val adminType = schema.getObjectType("Admin")
        val queryType = schema.queryType

        val reg = MockRequiredSelectionSetRegistry.builder()
            .fieldCheckerEntry("User" to "restricted", "id")
            .fieldResolverEntryForType("Query", "User" to "restricted", "__typename")
            .fieldCheckerEntry("Admin" to "restricted", "id")
            .fieldResolverEntryForType("Query", "AdminUser" to "restricted", "__typename")
            .build()

        val plan = buildPlan("{entity {restricted}}", ViaductSchema(schema), reg)
        val entityField = plan.selectionSet.selections[0] as Field

        val collected = CollectFields.shallowStrictCollect(
            schema,
            entityField.selectionSet!!,
            emptyVars,
            userType,
            plan.fragments
        )

        val collectedRestricted = collected.selections[0] as CollectedField

        expectThat(collectedRestricted.childPlans)
            .hasSize(2)
            .map { it.parentType }
            .and {
                contains(userType)
                contains(queryType)
            }

        expectThat(collectedRestricted.childPlans.map { it.parentType })
            .not()
            .contains(adminType)

        expectThat(collectedRestricted.fieldTypeChildPlans).isEmpty()
    }
}
