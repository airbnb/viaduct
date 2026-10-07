package viaduct.engine.runtime.execution

import graphql.execution.CoercedVariables
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import viaduct.arbitrary.graphql.asSchema
import viaduct.engine.api.EngineSchema
import viaduct.engine.runtime.execution.QueryPlan.Field

class CollectSubfieldsTest {
    private val schema = EngineSchema(
        """
            type Query { obj: Obj }
            type Obj { id: ID, name: String, bio: String, child: Obj }
        """.trimIndent().asSchema
    )
    private val objectType = schema.schema.getObjectType("Obj")
    private val emptyVars = CoercedVariables.emptyVariables()

    @Test
    fun `subfields preserve each parent context and report only newly encountered defers`() {
        val plan = buildPlan(
            """
                {
                    obj { ...F }
                    ... @defer(label: "A") {
                        obj {
                            ...F
                            name
                            ... @defer(label: "B") { bio }
                            child { ... @defer(label: "C") { bio } }
                        }
                    }
                }
                fragment F on Obj { id }
            """.trimIndent(),
            schema,
        )
        for (collector in listOf(CollectFields.default, CollectFields.cached())) {
            val root = collectRoot(plan, collector)
            val a = root.newDeferUsages.single()

            val result = collectSubfields(plan, root.collectedFieldsMap.getValue("obj").occurrences, collector)

            val b = result.newDeferUsages.single()
            assertEquals("B", b.defer.label)
            assertSame(a, b.parent)
            assertEquals(listOf("id", "name", "bio", "child"), result.collectedFieldsMap.keys.toList())
            assertEquals(listOf(null, a), result.collectedFieldsMap.getValue("id").occurrences.map { it.deferUsage })
            assertSame(a, result.collectedFieldsMap.getValue("name").occurrences.single().deferUsage)
            assertSame(b, result.collectedFieldsMap.getValue("bio").occurrences.single().deferUsage)

            val executionPlan = BuildExecutionPlan(result.collectedFieldsMap)
            assertEquals(listOf("id"), executionPlan.collectedFieldsMap.keys.toList())
            assertEquals(listOf("name", "child"), executionPlan.newCollectedFieldsMaps.getValue(setOf(a)).keys.toList())
            assertEquals(listOf("bio"), executionPlan.newCollectedFieldsMaps.getValue(setOf(b)).keys.toList())

            val child = collectSubfields(plan, result.collectedFieldsMap.getValue("child").occurrences, collector)
            val c = child.newDeferUsages.single()
            assertEquals("C", c.defer.label)
            assertSame(a, c.parent)
            assertSame(c, child.collectedFieldsMap.getValue("bio").occurrences.single().deferUsage)
        }
    }

    @Test
    fun `separate field collections share directive identity and merge subfields as ordered sets`() {
        val plan = buildPlan(
            """
                { obj { ...F } obj { ...F } }
                fragment F on Obj {
                    ... @defer(label: "shared") { name }
                    id
                }
            """.trimIndent(),
            schema,
        )
        val fields = plan.selectionSet.selections.filterIsInstance<Field>().map { FieldDetails(it, null) }

        for (collector in listOf(CollectFields.default, CollectFields.cached())) {
            for (enabled in listOf(false, true)) {
                val result = collectSubfields(plan, fields, collector, enabled)

                assertEquals(listOf("name", "id"), result.collectedFieldsMap.keys.toList())
                assertEquals(1, result.collectedFieldsMap.getValue("name").occurrences.size)
                assertNull(result.collectedFieldsMap.getValue("id").occurrences.single().deferUsage)
                if (enabled) {
                    assertEquals(2, result.newDeferUsages.size)
                    assertEquals(result.newDeferUsages.first(), result.newDeferUsages.last())
                    assertEquals("shared", result.newDeferUsages.first().defer.label)
                    assertSame(result.newDeferUsages.first(), result.collectedFieldsMap.getValue("name").occurrences.single().deferUsage)
                } else {
                    assertTrue(result.newDeferUsages.isEmpty())
                    assertNull(result.collectedFieldsMap.getValue("name").occurrences.single().deferUsage)
                }
            }
        }
    }

    @Test
    fun `subfields collect the same selection set separately for different inherited contexts`() {
        val plan = buildPlan("{ obj { id ... @defer { name } } }", schema)
        val field = plan.selectionSet.selections.single() as Field
        val a = DeferUsage(mkDefer("A"), null)
        val b = DeferUsage(mkDefer("B"), null)

        for (collector in listOf(CollectFields.default, CollectFields.cached())) {
            val result = collectSubfields(plan, listOf(FieldDetails(field, a), FieldDetails(field, b)), collector)

            assertEquals(listOf(a, b), result.collectedFieldsMap.getValue("id").occurrences.map { it.deferUsage })
            assertEquals(listOf(a, b), result.newDeferUsages.map { it.parent })
            assertEquals(result.newDeferUsages.first().defer, result.newDeferUsages.last().defer)
            assertEquals(result.newDeferUsages, result.collectedFieldsMap.getValue("name").occurrences.map { it.deferUsage })
        }
    }

    @Test
    fun `subfields skip absent and empty selection sets`() {
        val plan = buildPlan("{ obj { id } }", schema)
        val field = plan.selectionSet.selections.single() as Field
        val usage = DeferUsage(mkDefer("A"), null)
        val fields = listOf(
            FieldDetails(field.copy(selectionSet = null), usage),
            FieldDetails(field.copy(selectionSet = QueryPlan.SelectionSet.empty(objectType)), usage),
        )

        val unusedCollector = CollectFields { _, _, _, _, _, _, _, _ ->
            error("Parents without subfields must not invoke the selection collector")
        }
        for (collector in listOf(CollectFields.default, CollectFields.cached(), unusedCollector)) {
            for (input in listOf(emptyList(), fields)) {
                val result = collectSubfields(plan, input, collector)

                assertTrue(result.collectedFieldsMap.isEmpty())
                assertTrue(result.newDeferUsages.isEmpty())
            }
        }
    }

    @Test
    fun `ordered union retains distinct defer contexts and all new defer usages`() {
        val plan = buildPlan("{ obj { id name } }", schema)
        val field = plan.selectionSet.selections.single() as Field
        val selectionSet = checkNotNull(field.selectionSet)
        val (id, name) = selectionSet.selections.filterIsInstance<Field>()
        val inherited = DeferUsage(mkDefer("A"), null)
        val nested = DeferUsage(mkDefer("B"), null)
        val nestedUnderA = DeferUsage(nested.defer, inherited)
        val immediateId = FieldDetails(id, null)
        val deferredId = FieldDetails(id, inherited)
        val immediateName = FieldDetails(name, nested)
        val deferredName = FieldDetails(name, nestedUnderA)
        val results = mapOf(
            null to CollectFields.Result(
                linkedMapOf(
                    "name" to CollectedField(listOf(immediateName), schema.schema),
                    "id" to CollectedField(listOf(immediateId), schema.schema),
                ),
                listOf(nested),
            ),
            inherited to CollectFields.Result(
                linkedMapOf(
                    "id" to CollectedField(listOf(deferredId), schema.schema),
                    "name" to CollectedField(listOf(deferredName), schema.schema),
                ),
                listOf(nestedUnderA),
            ),
        )
        val contexts = mutableListOf<DeferUsage?>()
        val parents = listOf(FieldDetails(field, null), FieldDetails(field, inherited), FieldDetails(field, null))

        val result = CollectSubfields(
            schema = schema,
            objectType = schema.schema.getObjectType("Obj"),
            fields = parents,
            variables = emptyVars,
            fragments = plan.fragments,
            fieldRssOriginFilteringKillSwitchEnabled = false,
            incrementalExecutionEnabled = true,
            collectFields = CollectFields { actualSchema, selections, variables, objectType, fragments, killSwitchEnabled, enabled, deferUsage ->
                assertSame(schema, actualSchema)
                assertSame(selectionSet, selections)
                assertSame(emptyVars, variables)
                assertSame(schema.schema.getObjectType("Obj"), objectType)
                assertSame(plan.fragments, fragments)
                assertFalse(killSwitchEnabled)
                assertTrue(enabled)
                contexts += deferUsage
                results.getValue(deferUsage)
            },
        )

        assertEquals(listOf(null, inherited, null), contexts)
        assertEquals(listOf("name", "id"), result.collectedFieldsMap.keys.toList())
        assertEquals(listOf(immediateName, deferredName), result.collectedFieldsMap.getValue("name").occurrences)
        assertEquals(listOf(immediateId, deferredId), result.collectedFieldsMap.getValue("id").occurrences)
        assertEquals(listOf(nested, nestedUnderA, nested), result.newDeferUsages)
    }

    @Nested
    inner class GraphQLJsTests {
        @Test
        fun `merges parallel fragments`() {
            val plan = buildPlan(
                """
                    { obj { id ...FragOne ...FragTwo } }
                    fragment FragOne on Obj {
                        name
                        child { name deeper: child { name } }
                    }
                    fragment FragTwo on Obj {
                        bio
                        child { bio deeper: child { bio } }
                    }
                """.trimIndent(),
                schema,
            )

            for (collector in listOf(CollectFields.default, CollectFields.cached())) {
                val root = collectRoot(plan, collector)
                val result = collectSubfields(plan, root.collectedFieldsMap.getValue("obj").occurrences, collector)
                val child = collectSubfields(plan, result.collectedFieldsMap.getValue("child").occurrences, collector)
                val deeper = collectSubfields(plan, child.collectedFieldsMap.getValue("deeper").occurrences, collector)

                assertEquals(listOf("id", "name", "child", "bio"), result.collectedFieldsMap.keys.toList())
                assertEquals(2, result.collectedFieldsMap.getValue("child").occurrences.size)
                assertEquals(listOf("name", "deeper", "bio"), child.collectedFieldsMap.keys.toList())
                assertEquals(listOf("name", "bio"), deeper.collectedFieldsMap.keys.toList())
                for (collection in listOf(result, child, deeper)) {
                    assertTrue(collection.newDeferUsages.isEmpty())
                    collection.collectedFieldsMap.values.forEach { field ->
                        field.occurrences.forEach { assertNull(it.deferUsage) }
                    }
                }
            }
        }

        @Test
        fun `separately emits defer fragments with different labels with varying subfields`() {
            val plan = buildPlan(
                """
                    {
                        ... @defer(label: "DeferID") { obj { id } }
                        ... @defer(label: "DeferName") { obj { name } }
                    }
                """.trimIndent(),
                schema,
            )

            for (collector in listOf(CollectFields.default, CollectFields.cached())) {
                val root = collectRoot(plan, collector)
                val (idDefer, nameDefer) = root.newDeferUsages
                val result = collectSubfields(plan, root.collectedFieldsMap.getValue("obj").occurrences, collector)

                assertEquals(listOf("DeferID", "DeferName"), root.newDeferUsages.map { it.defer.label })
                assertEquals(listOf("id", "name"), result.collectedFieldsMap.keys.toList())
                assertSame(idDefer, result.collectedFieldsMap.getValue("id").occurrences.single().deferUsage)
                assertSame(nameDefer, result.collectedFieldsMap.getValue("name").occurrences.single().deferUsage)
                assertTrue(result.newDeferUsages.isEmpty())
                val executionPlan = BuildExecutionPlan(result.collectedFieldsMap)
                assertTrue(executionPlan.collectedFieldsMap.isEmpty())
                assertEquals(listOf("id"), executionPlan.newCollectedFieldsMaps.getValue(setOf(idDefer)).keys.toList())
                assertEquals(listOf("name"), executionPlan.newCollectedFieldsMaps.getValue(setOf(nameDefer)).keys.toList())
            }
        }

        @Test
        fun `separately emits defer fragments with varying subfields of same priorities but different level of defers`() {
            val plan = buildPlan(
                """
                    {
                        obj { ... @defer(label: "DeferID") { id } }
                        ... @defer(label: "DeferName") { obj { name } }
                    }
                """.trimIndent(),
                schema,
            )

            for (collector in listOf(CollectFields.default, CollectFields.cached())) {
                val root = collectRoot(plan, collector)
                val nameDefer = root.newDeferUsages.single()
                val result = collectSubfields(plan, root.collectedFieldsMap.getValue("obj").occurrences, collector)
                val idDefer = result.newDeferUsages.single()

                assertEquals("DeferID", idDefer.defer.label)
                assertEquals("DeferName", nameDefer.defer.label)
                assertNull(idDefer.parent)
                assertNull(nameDefer.parent)
                assertEquals(listOf("id", "name"), result.collectedFieldsMap.keys.toList())
                assertSame(idDefer, result.collectedFieldsMap.getValue("id").occurrences.single().deferUsage)
                assertSame(nameDefer, result.collectedFieldsMap.getValue("name").occurrences.single().deferUsage)
                val executionPlan = BuildExecutionPlan(result.collectedFieldsMap)
                assertTrue(executionPlan.collectedFieldsMap.isEmpty())
                assertEquals(listOf("id"), executionPlan.newCollectedFieldsMaps.getValue(setOf(idDefer)).keys.toList())
                assertEquals(listOf("name"), executionPlan.newCollectedFieldsMaps.getValue(setOf(nameDefer)).keys.toList())
            }
        }

        @Test
        fun `bundles varying subfields by defer combination while masking a nested deferred alias`() {
            val plan = buildPlan(
                """
                    {
                        ... @defer { obj { id } }
                        ... @defer {
                            obj {
                                name
                                shouldBeWithNameDespiteAdditionalDefer: name
                                ... @defer { shouldBeWithNameDespiteAdditionalDefer: name }
                            }
                        }
                    }
                """.trimIndent(),
                schema,
            )

            for (collector in listOf(CollectFields.default, CollectFields.cached())) {
                val root = collectRoot(plan, collector)
                val (idDefer, nameDefer) = root.newDeferUsages
                val result = collectSubfields(plan, root.collectedFieldsMap.getValue("obj").occurrences, collector)
                val nestedDefer = result.newDeferUsages.single()
                val alias = "shouldBeWithNameDespiteAdditionalDefer"

                assertEquals(listOf("id", "name", alias), result.collectedFieldsMap.keys.toList())
                assertSame(nameDefer, nestedDefer.parent)
                assertEquals(listOf(nameDefer, nestedDefer), result.collectedFieldsMap.getValue(alias).occurrences.map { it.deferUsage })
                val executionPlan = BuildExecutionPlan(result.collectedFieldsMap)
                assertTrue(executionPlan.collectedFieldsMap.isEmpty())
                assertEquals(setOf(setOf(idDefer), setOf(nameDefer)), executionPlan.newCollectedFieldsMaps.keys)
                assertEquals(listOf("id"), executionPlan.newCollectedFieldsMaps.getValue(setOf(idDefer)).keys.toList())
                assertEquals(listOf("name", alias), executionPlan.newCollectedFieldsMaps.getValue(setOf(nameDefer)).keys.toList())
            }
        }
    }

    private fun collectRoot(
        plan: QueryPlan,
        collectFields: CollectFields = CollectFields.default,
    ): CollectFields.Result =
        collectFields(
            schema = schema,
            selectionSet = plan.selectionSet,
            variables = emptyVars,
            parentType = schema.schema.queryType,
            fragments = plan.fragments,
            fieldRssOriginFilteringKillSwitchEnabled = false,
            incrementalExecutionEnabled = true,
            deferUsage = null,
        )

    private fun collectSubfields(
        plan: QueryPlan,
        fields: List<FieldDetails>,
        collectFields: CollectFields = CollectFields.default,
        incrementalExecutionEnabled: Boolean = true,
    ): CollectFields.Result =
        CollectSubfields(
            schema = schema,
            objectType = objectType,
            fields = fields,
            variables = emptyVars,
            fragments = plan.fragments,
            fieldRssOriginFilteringKillSwitchEnabled = false,
            incrementalExecutionEnabled = incrementalExecutionEnabled,
            collectFields = collectFields,
        )
}
