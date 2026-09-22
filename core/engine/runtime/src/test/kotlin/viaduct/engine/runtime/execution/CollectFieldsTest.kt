package viaduct.engine.runtime.execution

import graphql.execution.CoercedVariables
import graphql.execution.ResultPath
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import viaduct.arbitrary.graphql.asSchema
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.mocks.MockRequiredSelectionSetRegistry
import viaduct.engine.runtime.execution.QueryPlan.Field
import viaduct.engine.runtime.execution.QueryPlan.InlineFragment
import viaduct.engine.runtime.execution.QueryPlan.SelectionSet

class CollectFieldsTest {
    private val emptyVars = CoercedVariables.emptyVariables()

    @Nested
    inner class DefaultTests {
        @Test
        fun `preserves response key and occurrence order across fragments`() {
            val schema = "type Query { x: Int, y: Int }".asEngineSchema
            val plan = buildPlan(
                """
                    { z: x ...F y a: x z: x }
                    fragment F on Query { a: x z: x }
                """.trimIndent(),
                schema
            )
            val rootFields = plan.selectionSet.selections.filterIsInstance<Field>()
            val fragmentFields = plan.fragments.getValue("F").selectionSet.selections.filterIsInstance<Field>()

            val result = CollectFields.default(
                schema,
                plan.selectionSet,
                emptyVars,
                schema.schema.queryType,
                plan.fragments,
                fieldRssOriginFilteringKillSwitchEnabled = false,
            )

            val fields = result.collectedFieldsMap
            assertEquals(listOf("z", "a", "y"), fields.keys.toList())
            assertEquals(listOf("z", "a", "y"), fields.values.map { it.responseKey })
            assertEquals(
                listOf(rootFields[0].field, fragmentFields[1].field, rootFields[3].field),
                fields.getValue("z").occurrences.map { it.field.field }
            )
            assertEquals(
                listOf(fragmentFields[0].field, rootFields[2].field),
                fields.getValue("a").occurrences.map { it.field.field }
            )
            assertEquals(listOf(rootFields[1].field), fields.getValue("y").occurrences.map { it.field.field })
            assertTrue(result.newDeferUsages.isEmpty())
        }

        @Test
        fun `leaves defer usages empty and visits deferred fragments once`() {
            val schema = "type Query { x: Int, y: Int }".asEngineSchema
            val plan = buildPlan(
                """
                    {
                        ... @defer(label: "inline") { x }
                        ...F @defer(label: "first")
                        ...F @defer(label: "second")
                    }
                    fragment F on Query { y }
                """.trimIndent(),
                schema
            )

            val result = CollectFields.default(
                schema,
                plan.selectionSet,
                emptyVars,
                schema.schema.queryType,
                plan.fragments,
                fieldRssOriginFilteringKillSwitchEnabled = false,
            )

            assertEquals(listOf("x", "y"), result.collectedFieldsMap.keys.toList())
            assertTrue(result.newDeferUsages.isEmpty())
            result.collectedFieldsMap.values.forEach { field ->
                assertNull(field.occurrences.single().deferUsage)
            }
        }

        @Test
        fun `single field`() {
            val schema = "type Query { x:Int }".asEngineSchema
            val plan = buildPlan("{x}", schema)
            val xField = plan.selectionSet.selections.first() as Field

            val collected = CollectFields.default(
                schema,
                plan.selectionSet,
                emptyVars,
                schema.schema.queryType,
                plan.fragments,
                fieldRssOriginFilteringKillSwitchEnabled = false,
            )

            checkEquals(
                collected.collectedFieldsMap.values,
                listOf(
                    CollectedField(listOf(FieldDetails(xField, null)), schema.schema)
                )
            )
        }

        @Test
        fun `single skipped field`() {
            val schema = "type Query { x:Int }".asEngineSchema
            val plan = buildPlan("{x @skip(if:\$var) }", schema)

            val collected = CollectFields.default(
                schema,
                plan.selectionSet,
                CoercedVariables.of(mapOf("var" to true)),
                schema.schema.queryType,
                plan.fragments,
                fieldRssOriginFilteringKillSwitchEnabled = false,
            )

            checkEquals(
                collected.collectedFieldsMap.values,
                emptyList()
            )
            assertTrue(collected.newDeferUsages.isEmpty())
        }

        @Test
        fun `mergeable fields`() {
            val schema = "type Query { x:Int }".asEngineSchema
            val plan = buildPlan("{x x}", schema)
            val x0 = plan.selectionSet.selections[0] as Field
            val x1 = plan.selectionSet.selections[1] as Field

            val collected = CollectFields.default(
                schema,
                plan.selectionSet,
                emptyVars,
                schema.schema.queryType,
                plan.fragments,
                fieldRssOriginFilteringKillSwitchEnabled = false,
            )

            assertEquals(listOf(x0.field, x1.field), collected.collectedFieldsMap.values.single().occurrences.map { it.field.field })
            assertEquals(listOf(null, null), collected.collectedFieldsMap.values.single().occurrences.map { it.deferUsage })
            checkEquals(
                collected.collectedFieldsMap.values,
                listOf(
                    CollectedField(listOf(FieldDetails(x0, null), FieldDetails(x1, null)), schema.schema)
                )
            )
        }

        @Test
        fun `inline fragment`() {
            val schema = "type Query { x:Int }".asEngineSchema
            val plan = buildPlan("{ ... {x}}", schema)
            val xField = (plan.selectionSet.selections.first() as InlineFragment)
                .selectionSet.selections.first() as Field

            val collected = CollectFields.default(
                schema,
                plan.selectionSet,
                emptyVars,
                schema.schema.queryType,
                plan.fragments,
                fieldRssOriginFilteringKillSwitchEnabled = false,
            )

            checkEquals(
                collected.collectedFieldsMap.values,
                listOf(
                    CollectedField(listOf(FieldDetails(xField, null)), schema.schema)
                )
            )
        }

        @Test
        fun `fragment spread`() {
            val schema = "type Query { x:Int }".asEngineSchema
            val plan = buildPlan(
                """
                    { ...F, ...F }
                    fragment F on Query { x }
                """.trimIndent(),
                schema
            )
            val xField = plan.fragments["F"]!!.selectionSet.selections.first() as Field

            val collected = CollectFields.default(
                schema,
                plan.selectionSet,
                emptyVars,
                schema.schema.queryType,
                plan.fragments,
                fieldRssOriginFilteringKillSwitchEnabled = false,
            )

            checkEquals(
                collected.collectedFieldsMap.values,
                listOf(
                    CollectedField(listOf(FieldDetails(xField, null)), schema.schema)
                )
            )
        }

        @Test
        fun `filters child plans to constrained type and root types`() {
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
            """.asEngineSchema

            val userType = schema.schema.getObjectType("User")
            val adminType = schema.schema.getObjectType("Admin")
            val queryType = schema.schema.queryType

            val reg = MockRequiredSelectionSetRegistry.builder()
                .fieldCheckerEntry("User" to "restricted", "id")
                .fieldResolverEntryForType("Query", "User" to "restricted", "__typename")
                .fieldCheckerEntry("Admin" to "restricted", "id")
                .fieldResolverEntryForType("Query", "AdminUser" to "restricted", "__typename")
                .build()

            val plan = buildPlan("{entity {restricted}}", schema, reg)
            val entityField = plan.selectionSet.selections[0] as Field

            val collected = CollectFields.default(
                schema,
                entityField.selectionSet!!,
                emptyVars,
                userType,
                plan.fragments,
                fieldRssOriginFilteringKillSwitchEnabled = false,
            )

            val collectedRestricted = collected.collectedFieldsMap.getValue("restricted")
            val collectedRestrictedParentTypes =
                collectedRestricted.childPlans.map { it.queryPlanParentType }

            collectedRestrictedParentTypes.shouldHaveSize(2)
            collectedRestrictedParentTypes shouldContain userType
            collectedRestrictedParentTypes shouldContain queryType
            collectedRestrictedParentTypes shouldNotContain adminType

            collectedRestricted.fieldTypeChildPlans.plansFor(queryType).shouldHaveSize(0)
        }

        @Test
        fun `drops sibling-implementor RSS when origin-coordinate enforcement is on`() {
            val fx = buildOriginCoordinateFixture()

            // Runtime type is HiveTable. With origin-coordinate enforcement on (the default),
            // the OtherNode.id RSS — even though it's rooted on Query (a root type that the
            // legacy filter would have permissively allowed) — must be dropped.
            val collected = CollectFields.default(
                fx.schema,
                fx.nodeSelectionSet,
                emptyVars,
                fx.hiveTable,
                fx.fragments,
                fieldRssOriginFilteringKillSwitchEnabled = false,
            )

            val collectedId = collected.collectedFieldsMap.values.single { it.fieldName == "id" }
            // Only the HiveTable.id RSS should survive.
            collectedId.childPlans.shouldHaveSize(1)
            assertEquals("HiveTable" to "id", collectedId.childPlans.single().originCoordinate)
        }

        @Test
        fun `keeps sibling-implementor RSS when killswitch reverts to legacy filter`() {
            val fx = buildOriginCoordinateFixture()

            // Killswitch enabled reverts to legacy behavior: the OtherNode.id RSS rooted on Query
            // slips through (this is the bug we're fixing, pinned here to prove the toggle works).
            val collected = CollectFields.default(
                fx.schema,
                fx.nodeSelectionSet,
                emptyVars,
                fx.hiveTable,
                fx.fragments,
                fieldRssOriginFilteringKillSwitchEnabled = true,
            )

            val collectedId = collected.collectedFieldsMap.values.single { it.fieldName == "id" }
            // Both RSS entries are kept under legacy filter — HiveTable's matches by parentType,
            // OtherNode's slips through via the root-type permissive clause.
            collectedId.childPlans.shouldHaveSize(2)
            val origins = collectedId.childPlans.map { it.originCoordinate }.toSet()
            assertEquals(setOf("HiveTable" to "id", "OtherNode" to "id"), origins)
        }

        @Test
        fun `keeps own-implementor RSS when collecting that implementor`() {
            val fx = buildOriginCoordinateFixture()

            // Runtime type is OtherNode. The OtherNode.id RSS should survive, the HiveTable.id one
            // should be dropped.
            val collected = CollectFields.default(
                fx.schema,
                fx.nodeSelectionSet,
                emptyVars,
                fx.otherNode,
                fx.fragments,
                fieldRssOriginFilteringKillSwitchEnabled = false,
            )

            val collectedId = collected.collectedFieldsMap.values.single { it.fieldName == "id" }
            collectedId.childPlans.shouldHaveSize(1)
            assertEquals("OtherNode" to "id", collectedId.childPlans.single().originCoordinate)
        }

        @Test
        fun `keeps Query-rooted RSS whose origin matches the runtime field`() {
            // Regression guard for the "isRootType clause is durable" rule: a legitimate
            // matching-origin RSS can be Query-rooted (e.g., its required fragment selects
            // root fields). The origin-coordinate filter must not drop it.
            val schema = """
                type Query { x: Int z: Int }
            """.asEngineSchema

            val reg = MockRequiredSelectionSetRegistry.builder()
                // Field-checker for Query.x with an RSS rooted on Query.
                .fieldCheckerEntry("Query" to "x", "z")
                .build()

            val plan = buildPlan("{x}", schema, reg)
            val collected = CollectFields.default(
                schema,
                plan.selectionSet,
                emptyVars,
                schema.schema.queryType,
                plan.fragments,
                fieldRssOriginFilteringKillSwitchEnabled = false,
            )

            val collectedX = collected.collectedFieldsMap.values.single { it.fieldName == "x" }
            collectedX.childPlans.shouldHaveSize(1)
            assertEquals("Query" to "x", collectedX.childPlans.single().originCoordinate)
        }

        @Test
        fun `merges same-response-key fields whose declared parent types are an interface and its implementor`() {
            // Regression test for a production incident: a selection on an interface field
            // (`Sections.metadata: SectionsMetadata`) and a selection on an implementing type's
            // narrower override of that field (`MediationSections.metadata: MediationMetadata`)
            // share the same response key `sectionMetadata`. Both apply against a runtime type of
            // MediationSections, so their subselection sets must be merged rather than rejected.
            val schema = """
                type Query { configuration: Sections }

                interface Sections { metadata: SectionsMetadata }
                interface SectionsMetadata { pageTitle: String }

                type MediationSections implements Sections {
                    metadata: MediationMetadata
                }

                type MediationMetadata implements SectionsMetadata {
                    pageTitle: String
                    prefillValues: String
                }
            """.asEngineSchema
            val mediationSections = schema.schema.getObjectType("MediationSections")

            val plan = buildPlan(
                """
                    {
                        configuration {
                            ... on Sections { sectionMetadata: metadata { pageTitle } }
                            ... on MediationSections { sectionMetadata: metadata { prefillValues } }
                        }
                    }
                """.trimIndent(),
                schema
            )
            val configurationField = plan.selectionSet.selections.single() as Field

            val collected = CollectFields.default(
                schema,
                configurationField.selectionSet!!,
                emptyVars,
                mediationSections,
                plan.fragments,
                fieldRssOriginFilteringKillSwitchEnabled = false,
            )

            val collectedMetadata = collected.collectedFieldsMap.values.single { it.responseKey == "sectionMetadata" }
            val mergedSelectionSet = requireNotNull(collectedMetadata.selectionSet)
            assertEquals(schema.schema.getObjectType("MediationMetadata"), mergedSelectionSet.parentType)

            val mergedFieldNames = mergedSelectionSet.selections.filterIsInstance<Field>().map { it.field.name }
            mergedFieldNames shouldContain "pageTitle"
            mergedFieldNames shouldContain "prefillValues"
        }
    }

    @Nested
    inner class CachedTests {
        @Test
        fun `collect returns cached result for same parentType and selectionSet`() {
            val schema = "type Query { x: Int, y: String }".asSchema
            val plan = buildPlan("{ x y }", EngineSchema(schema))

            var collections = 0
            val cache = CollectFields.cached { engineSchema, selectionSet, variables, parentType, fragments, killSwitchEnabled ->
                collections++
                CollectFields.default(engineSchema, selectionSet, variables, parentType, fragments, killSwitchEnabled)
            }

            val result1 = cache.collectForTest(schema, plan.selectionSet, emptyVars, schema.queryType, plan.fragments)
            val result2 = cache.collectForTest(schema, plan.selectionSet, emptyVars, schema.queryType, plan.fragments)

            result1.collectedFieldsMap.values.shouldHaveSize(2)
            assertEquals(listOf("x", "y"), result1.collectedFieldsMap.keys.toList())
            assertTrue(result1.newDeferUsages.isEmpty())
            assertSame(result1, result2)
            assertEquals(1, collections)
        }

        @Test
        fun `collect returns different results for different parentTypes`() {
            val schema = """
                type Query { x: Int }
                type Mutation { y: Int }
            """.trimIndent().asSchema
            val queryPlan = buildPlan("{ x }", EngineSchema(schema))
            val mutationPlan = buildPlan("mutation { y }", EngineSchema(schema))

            val cache = CollectFields.cached()

            val queryResult = cache.collectForTest(schema, queryPlan.selectionSet, emptyVars, schema.queryType, queryPlan.fragments)
            val mutationResult = cache.collectForTest(schema, mutationPlan.selectionSet, emptyVars, schema.mutationType!!, mutationPlan.fragments)

            queryResult.collectedFieldsMap.values.shouldHaveSize(1)
            mutationResult.collectedFieldsMap.values.shouldHaveSize(1)
            assertEquals("x", queryResult.collectedFieldsMap.getValue("x").responseKey)
            assertEquals("y", mutationResult.collectedFieldsMap.getValue("y").responseKey)
        }

        @Test
        fun `collect uses identity-based cache key`() {
            val schema = "type Query { x: Int }".asSchema
            val plan = buildPlan("{ x }", EngineSchema(schema))

            val cache = CollectFields.cached()

            val result1 = cache.collectForTest(schema, plan.selectionSet, emptyVars, schema.queryType, plan.fragments)
            val result2 = cache.collectForTest(schema, plan.selectionSet, emptyVars, schema.queryType, plan.fragments)
            val result3 = cache.collectForTest(schema, plan.selectionSet, emptyVars, schema.queryType, plan.fragments)

            assertSame(result1, result2)
            assertSame(result2, result3)
        }

        @Test
        fun `collect caches nested selection sets independently`() {
            val schema = """
                type Query { foo: Foo }
                type Foo { bar: String, baz: Int }
            """.trimIndent().asSchema
            val plan = buildPlan("{ foo { bar baz } }", EngineSchema(schema))
            val fooType = schema.getObjectType("Foo")
            val fooField = plan.selectionSet.selections[0] as QueryPlan.Field
            val fooSelectionSet = fooField.selectionSet!!

            val cache = CollectFields.cached()

            val queryResult = cache.collectForTest(schema, plan.selectionSet, emptyVars, schema.queryType, plan.fragments)
            val fooResult = cache.collectForTest(schema, fooSelectionSet, emptyVars, fooType, plan.fragments)

            queryResult.collectedFieldsMap.values.shouldHaveSize(1)
            fooResult.collectedFieldsMap.values.shouldHaveSize(2)

            val fooResultAgain = cache.collectForTest(schema, fooSelectionSet, emptyVars, fooType, plan.fragments)
            assertSame(fooResult, fooResultAgain)
        }

        @Test
        fun `collect keys nested selection sets on enclosing directive variables but ignores enclosing argument variables`() {
            val schema = """
                type Query { user(id: ID): User }
                type User { id: ID, name: String }
            """.trimIndent().asSchema
            val plan = buildPlan(
                """
                    query(${'$'}includeUser: Boolean!, ${'$'}id: ID) {
                        user(id: ${'$'}id) @include(if: ${'$'}includeUser) {
                            id
                            name
                        }
                    }
                """.trimIndent(),
                EngineSchema(schema)
            )
            val userField = plan.selectionSet.selections.single() as QueryPlan.Field
            val userSelectionSet = userField.selectionSet!!
            val userType = schema.getObjectType("User")
            val cache = CollectFields.cached()

            val includeUserResult =
                cache.collectForTest(schema, userSelectionSet, CoercedVariables.of(mapOf("includeUser" to true, "id" to "1")), userType, plan.fragments)
            val sameDirectiveDifferentArgumentResult =
                cache.collectForTest(schema, userSelectionSet, CoercedVariables.of(mapOf("includeUser" to true, "id" to "2")), userType, plan.fragments)
            val skipUserResult =
                cache.collectForTest(schema, userSelectionSet, CoercedVariables.of(mapOf("includeUser" to false, "id" to "1")), userType, plan.fragments)

            assertEquals(listOf("id", "name"), includeUserResult.collectedFieldsMap.keys.toList())
            assertSame(includeUserResult, sameDirectiveDifferentArgumentResult)
            assertNotSame(includeUserResult, skipUserResult)
            skipUserResult.collectedFieldsMap.values.shouldHaveSize(0)
        }

        @Test
        fun `collect keys on directive variables but ignores argument-only variables`() {
            val schema = "type Query { x(id: ID): Int, y: Int }".asSchema
            val plan = buildPlan("{ x(id: ${'$'}id) @include(if: ${'$'}directive), y @skip(if: ${'$'}directive) }", EngineSchema(schema))
            val cache = CollectFields.cached()

            val firstResult =
                cache.collectForTest(schema, plan.selectionSet, CoercedVariables.of(mapOf("directive" to true, "id" to "1")), schema.queryType, plan.fragments)
            val sameDirectiveDifferentArgumentResult =
                cache.collectForTest(schema, plan.selectionSet, CoercedVariables.of(mapOf("directive" to true, "id" to "2")), schema.queryType, plan.fragments)
            val differentDirectiveResult =
                cache.collectForTest(schema, plan.selectionSet, CoercedVariables.of(mapOf("directive" to false, "id" to "1")), schema.queryType, plan.fragments)

            assertEquals("x", firstResult.collectedFieldsMap.values.single().responseKey)
            assertSame(firstResult, sameDirectiveDifferentArgumentResult)
            assertNotSame(firstResult, differentDirectiveResult)
            assertEquals("y", differentDirectiveResult.collectedFieldsMap.values.single().responseKey)
        }

        @Test
        fun `collect keys on directive variables from fragment spreads and fragment bodies`() {
            val schema = "type Query { x: Int, y: Int }".asSchema
            val plan = buildPlan(
                """
                    query(${'$'}spread: Boolean!, ${'$'}field: Boolean!) {
                        y
                        ...F @skip(if: ${'$'}spread)
                    }

                    fragment F on Query {
                        x @include(if: ${'$'}field)
                    }
                """.trimIndent(),
                EngineSchema(schema)
            )
            val cache = CollectFields.cached()

            val includeFragmentResult =
                cache.collectForTest(schema, plan.selectionSet, CoercedVariables.of(mapOf("spread" to false, "field" to true)), schema.queryType, plan.fragments)
            val excludeFragmentFieldResult =
                cache.collectForTest(schema, plan.selectionSet, CoercedVariables.of(mapOf("spread" to false, "field" to false)), schema.queryType, plan.fragments)
            val skipFragmentResult =
                cache.collectForTest(schema, plan.selectionSet, CoercedVariables.of(mapOf("spread" to true, "field" to true)), schema.queryType, plan.fragments)

            assertEquals(listOf("y", "x"), includeFragmentResult.collectedFieldsMap.keys.toList())
            assertNotSame(includeFragmentResult, excludeFragmentFieldResult)
            assertEquals(listOf("y"), excludeFragmentFieldResult.collectedFieldsMap.keys.toList())
            assertNotSame(includeFragmentResult, skipFragmentResult)
            assertEquals(listOf("y"), skipFragmentResult.collectedFieldsMap.keys.toList())
        }

        @Test
        fun `collect ignores directive variables in type-pruned fragments`() {
            val schema = """
                interface Node { id: ID }
                type User implements Node { id: ID, name: String }
                type Listing implements Node { id: ID, title: String }
                type Query { node: Node }
            """.trimIndent().asSchema
            val plan = buildPlan(
                """
                    query {
                        node {
                            id
                            ...UserFields
                        }
                    }

                    fragment UserFields on User {
                        name @include(if: ${'$'}includeName)
                    }
                """.trimIndent(),
                EngineSchema(schema)
            )
            val nodeField = plan.selectionSet.selections.single() as QueryPlan.Field
            val nodeSelectionSet = nodeField.selectionSet!!
            val listingType = schema.getObjectType("Listing")
            val cache = CollectFields.cached()

            val includeNameResult =
                cache.collectForTest(schema, nodeSelectionSet, CoercedVariables.of(mapOf("includeName" to true)), listingType, plan.fragments)
            val skipNameResult =
                cache.collectForTest(schema, nodeSelectionSet, CoercedVariables.of(mapOf("includeName" to false)), listingType, plan.fragments)

            assertEquals("id", includeNameResult.collectedFieldsMap.values.single().responseKey)
            assertSame(includeNameResult, skipNameResult)
        }
    }

    /**
     * Schema and registry shared across the origin-coordinate regression tests.
     * Mirrors the prod-observed shape: an interface field selected via concrete-type spread,
     * with one implementor's RSS rooted on Query (the leaker) and the other's rooted on
     * its own type.
     */
    private data class OriginCoordinateFixture(
        val rawSchema: GraphQLSchema,
        val schema: EngineSchema,
        val nodeField: Field,
        val nodeSelectionSet: SelectionSet,
        val fragments: QueryPlan.Fragments,
        val hiveTable: GraphQLObjectType,
        val otherNode: GraphQLObjectType,
    )

    private fun buildOriginCoordinateFixture(): OriginCoordinateFixture {
        val schema = """
            type Query { node: Node }

            interface Node { id: ID! }

            type HiveTable implements Node {
                id: ID!
                urn: String
            }

            type OtherNode implements Node {
                id: ID!
            }
        """.asEngineSchema

        val reg = MockRequiredSelectionSetRegistry.builder()
            // OtherNode.id checker RSS is rooted on Query — matches the prod-observed leaker.
            .fieldCheckerEntry("OtherNode" to "id", "node { __typename }", selectionsType = "Query")
            // HiveTable.id checker RSS is rooted on HiveTable.
            .fieldCheckerEntry("HiveTable" to "id", "urn")
            .build()

        val plan = buildPlan("{node { id ... on HiveTable { urn } }}", schema, reg)
        val nodeField = plan.selectionSet.selections.single() as Field
        return OriginCoordinateFixture(
            rawSchema = schema.schema,
            schema = schema,
            nodeField = nodeField,
            nodeSelectionSet = nodeField.selectionSet!!,
            fragments = plan.fragments,
            hiveTable = schema.schema.getObjectType("HiveTable"),
            otherNode = schema.schema.getObjectType("OtherNode"),
        )
    }
}

private fun CollectFields.collectForTest(
    schema: GraphQLSchema,
    selectionSet: SelectionSet,
    variables: CoercedVariables,
    parentType: GraphQLObjectType,
    fragments: QueryPlan.Fragments,
): CollectFields.Result =
    invoke(
        EngineSchema(schema),
        selectionSet,
        variables,
        parentType,
        fragments,
        fieldRssOriginFilteringKillSwitchEnabled = false,
    )

private val String.asEngineSchema: EngineSchema
    get() = EngineSchema(asSchema)

private fun checkEquals(
    exp: Collection<CollectedField>,
    act: Collection<CollectedField>,
) {
    act.shouldHaveSize(exp.size)
    exp.zip(act).forEach { (exp, act) ->
        assertEquals(exp.responseKey, act.responseKey)
        val expSelectionSet = exp.selectionSet
        if (expSelectionSet != null) {
            assertNotNull(act.selectionSet)
            checkEquals(expSelectionSet, act.selectionSet!!)
        } else {
            assertNull(act.selectionSet)
        }
        assertMergedFieldsEqual(ResultPath.rootPath(), exp.mergedField, act.mergedField)
        checkEqualsFieldChildPlanList(exp.childPlans, act.childPlans)
    }
}
