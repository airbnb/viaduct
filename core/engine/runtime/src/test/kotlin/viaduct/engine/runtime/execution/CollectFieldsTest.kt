package viaduct.engine.runtime.execution

import graphql.execution.CoercedVariables
import graphql.execution.MergedField
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.hasSize
import strikt.assertions.isEmpty
import strikt.assertions.isEqualTo
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
            plan.fragments,
            fieldRssOriginFilteringKillSwitchEnabled = false,
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
            plan.fragments,
            fieldRssOriginFilteringKillSwitchEnabled = false,
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
            plan.fragments,
            fieldRssOriginFilteringKillSwitchEnabled = false,
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
            plan.fragments,
            fieldRssOriginFilteringKillSwitchEnabled = false,
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
            plan.fragments,
            fieldRssOriginFilteringKillSwitchEnabled = false,
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
            plan.fragments,
            fieldRssOriginFilteringKillSwitchEnabled = false,
        )

        val collectedRestricted = collected.selections[0] as CollectedField

        expectThat(collectedRestricted.childPlans)
            .hasSize(2)
            .map { it.plan.parentType }
            .and {
                contains(userType)
                contains(queryType)
            }

        expectThat(collectedRestricted.childPlans.map { it.plan.parentType })
            .not()
            .contains(adminType)

        expectThat(collectedRestricted.fieldTypeChildPlans).isEmpty()
    }

    /**
     * Schema and registry shared across the origin-coordinate regression tests below.
     * Mirrors the prod-observed shape: an interface field selected via concrete-type spread,
     * with one implementor's RSS rooted on Query (the leaker) and the other's rooted on
     * its own type.
     */
    private data class OriginCoordinateFixture(
        val rawSchema: graphql.schema.GraphQLSchema,
        val schema: ViaductSchema,
        val nodeField: Field,
        val nodeSelectionSet: SelectionSet,
        val fragments: QueryPlan.Fragments,
        val hiveTable: graphql.schema.GraphQLObjectType,
        val otherNode: graphql.schema.GraphQLObjectType,
    )

    private fun buildOriginCoordinateFixture(): OriginCoordinateFixture {
        val rawSchema = """
            type Query { node: Node }

            interface Node { id: ID! }

            type HiveTable implements Node {
                id: ID!
                urn: String
            }

            type OtherNode implements Node {
                id: ID!
            }
        """.asSchema
        val schema = ViaductSchema(rawSchema)

        val reg = MockRequiredSelectionSetRegistry.builder()
            // OtherNode.id checker RSS is rooted on Query — matches the prod-observed leaker.
            .fieldCheckerEntry("OtherNode" to "id", "node { __typename }", selectionsType = "Query")
            // HiveTable.id checker RSS is rooted on HiveTable.
            .fieldCheckerEntry("HiveTable" to "id", "urn")
            .build()

        val plan = buildPlan("{node { id ... on HiveTable { urn } }}", schema, reg)
        val nodeField = plan.selectionSet.selections.single() as Field
        return OriginCoordinateFixture(
            rawSchema = rawSchema,
            schema = schema,
            nodeField = nodeField,
            nodeSelectionSet = nodeField.selectionSet!!,
            fragments = plan.fragments,
            hiveTable = rawSchema.getObjectType("HiveTable"),
            otherNode = rawSchema.getObjectType("OtherNode"),
        )
    }

    @Test
    fun `shallowStrictCollect drops sibling-implementor RSS when origin-coordinate enforcement is on`() {
        val fx = buildOriginCoordinateFixture()

        // Runtime type is HiveTable. With origin-coordinate enforcement on (the default),
        // the OtherNode.id RSS — even though it's rooted on Query (a root type that the
        // legacy filter would have permissively allowed) — must be dropped.
        val collected = CollectFields.shallowStrictCollect(
            fx.rawSchema,
            fx.nodeSelectionSet,
            emptyVars,
            fx.hiveTable,
            fx.fragments,
            fieldRssOriginFilteringKillSwitchEnabled = false,
        )

        val collectedId = collected.selections.filterIsInstance<CollectedField>().single { it.fieldName == "id" }
        // Only the HiveTable.id RSS should survive.
        expectThat(collectedId.childPlans).hasSize(1)
        expectThat(collectedId.childPlans.single().originCoordinate).isEqualTo("HiveTable" to "id")
    }

    @Test
    fun `shallowStrictCollect keeps sibling-implementor RSS when killswitch reverts to legacy filter`() {
        val fx = buildOriginCoordinateFixture()

        // Killswitch enabled reverts to legacy behavior: the OtherNode.id RSS rooted on Query
        // slips through (this is the bug we're fixing, pinned here to prove the toggle works).
        val collected = CollectFields.shallowStrictCollect(
            fx.rawSchema,
            fx.nodeSelectionSet,
            emptyVars,
            fx.hiveTable,
            fx.fragments,
            fieldRssOriginFilteringKillSwitchEnabled = true,
        )

        val collectedId = collected.selections.filterIsInstance<CollectedField>().single { it.fieldName == "id" }
        // Both RSS entries are kept under legacy filter — HiveTable's matches by parentType,
        // OtherNode's slips through via the root-type permissive clause.
        expectThat(collectedId.childPlans).hasSize(2)
        val origins = collectedId.childPlans.map { it.originCoordinate }.toSet()
        expectThat(origins).isEqualTo(setOf("HiveTable" to "id", "OtherNode" to "id"))
    }

    @Test
    fun `shallowStrictCollect keeps own-implementor RSS when collecting that implementor`() {
        val fx = buildOriginCoordinateFixture()

        // Runtime type is OtherNode. The OtherNode.id RSS should survive, the HiveTable.id one
        // should be dropped.
        val collected = CollectFields.shallowStrictCollect(
            fx.rawSchema,
            fx.nodeSelectionSet,
            emptyVars,
            fx.otherNode,
            fx.fragments,
            fieldRssOriginFilteringKillSwitchEnabled = false,
        )

        val collectedId = collected.selections.filterIsInstance<CollectedField>().single { it.fieldName == "id" }
        expectThat(collectedId.childPlans).hasSize(1)
        expectThat(collectedId.childPlans.single().originCoordinate).isEqualTo("OtherNode" to "id")
    }

    @Test
    fun `shallowStrictCollect keeps Query-rooted RSS whose origin matches the runtime field`() {
        // Regression guard for the "isRootType clause is durable" rule: a legitimate
        // matching-origin RSS can be Query-rooted (e.g., its required fragment selects
        // root fields). The origin-coordinate filter must not drop it.
        val rawSchema = """
            type Query { x: Int z: Int }
        """.asSchema
        val schema = ViaductSchema(rawSchema)

        val reg = MockRequiredSelectionSetRegistry.builder()
            // Field-checker for Query.x with an RSS rooted on Query.
            .fieldCheckerEntry("Query" to "x", "z")
            .build()

        val plan = buildPlan("{x}", schema, reg)
        val collected = CollectFields.shallowStrictCollect(
            rawSchema,
            plan.selectionSet,
            emptyVars,
            rawSchema.queryType,
            plan.fragments,
            fieldRssOriginFilteringKillSwitchEnabled = false,
        )

        val collectedX = collected.selections.filterIsInstance<CollectedField>().single { it.fieldName == "x" }
        expectThat(collectedX.childPlans).hasSize(1)
        expectThat(collectedX.childPlans.single().originCoordinate).isEqualTo("Query" to "x")
    }
}
