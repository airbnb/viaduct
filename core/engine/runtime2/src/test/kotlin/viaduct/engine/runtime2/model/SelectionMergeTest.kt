package viaduct.engine.runtime2.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.testRoot
import viaduct.graphql.schema.ViaductSchema

class SelectionMergeTest {
    @Test
    fun `object keys classify selections precisely`() {
        val fixture = Fixture()
        val concrete = fixture.selection("Query", "item")
        val abstract =
            fixture.selection(
                typeName = "Item",
                fieldName = "computed",
                possibleTypes = setOf(fixture.item),
            )

        assertIs<ObjectSelection>(concrete)
        assertFalse(abstract is ObjectSelection)
    }

    @Test
    fun `duplicate leaves merge while distinct argument tuples remain separate`() {
        val fixture = Fixture()
        val one = fixture.selection("Query", "scalar", mapOf("arg" to 1))
        val two = fixture.selection("Query", "scalar", mapOf("arg" to 2))
        val forest = selectionForestOf(one, one, two)

        val merged = forest.merge(fixture.query)
        val oneKey =
            ObjectEngineResult.GroundKey.of(
                fixture.schema.requireObjectField("Query", "scalar"),
                mapOf("arg" to 1),
            )
        val missingKey =
            ObjectEngineResult.GroundKey.of(
                fixture.schema.requireObjectField("Query", "scalar"),
                mapOf("arg" to 3),
            )

        assertEquals(fixture.query, merged.type)
        assertEquals(2, merged.size)
        assertEquals(setOf(one.key, two.key), merged.keys())
        assertEquals(merged.keys(), merged.byKey().keys)
        assertSame(merged.byKey().getValue(oneKey), merged[oneKey])
        assertFailsWith<NoSuchElementException> { merged[missingKey] }
        val filtered: ObjectSelectionForest =
            merged.filter { selection -> selection.key == oneKey }
        assertEquals(setOf(oneKey), filtered.keys())
        merged.forEach { selection ->
            assertIs<ObjectSelection>(selection)
            assertEquals(fixture.query, selection.key.field.containingDef)
            assertEquals(setOf(fixture.query), selection.possibleTypes)
            assertTrue(selection.subselections.isEmpty())
        }
    }

    @Test
    fun `object selection forest validates parent type exclusive applicability and unique keys`() {
        val fixture = Fixture()
        val querySelection =
            assertIs<ObjectSelection>(fixture.selection("Query", "item"))
        val itemSelection =
            assertIs<ObjectSelection>(fixture.selection("ConcreteItem", "a"))
        val inapplicableQuerySelection =
            ObjectSelection.of(
                key = querySelection.key,
                possibleTypes = emptySet(),
                subselections = querySelection.subselections,
            )

        assertFailsWith<IllegalArgumentException> {
            ObjectSelectionForest.of(fixture.query, listOf(itemSelection))
        }
        assertFailsWith<IllegalArgumentException> {
            ObjectSelectionForest.of(fixture.query, listOf(inapplicableQuerySelection))
        }
        assertFailsWith<IllegalArgumentException> {
            ObjectSelectionForest.of(fixture.query, listOf(querySelection, querySelection))
        }
    }

    @Test
    fun `composite duplicates concatenate children without recursively merging them`() {
        val fixture = Fixture()
        val a = fixture.selection("ConcreteItem", "a")
        val b = fixture.selection("ConcreteItem", "b")
        val first = fixture.selection("Query", "item", subselections = selectionForestOf(a))
        val second =
            fixture.selection(
                "Query",
                "item",
                subselections = selectionForestOf(a, b),
            )

        val merged = selectionForestOf(first, second).merge(fixture.query)

        val item = merged.single()
        assertEquals(3, item.subselections.size)
        assertEquals(
            setOf(a.key, b.key),
            item.subselections.merge(fixture.item).keys(),
        )
        assertEquals(
            2,
            item.subselections.merge(fixture.item).size,
        )
    }

    @Test
    fun `forest concatenation preserves every occurrence`() {
        val fixture = Fixture()
        val a = fixture.selection("ConcreteItem", "a")
        val b = fixture.selection("ConcreteItem", "b")

        val concatenated =
            listOf(
                selectionForestOf(a),
                selectionForestOf(),
                selectionForestOf(a, b),
            ).concatenateSelectionForests()

        assertEquals(3, concatenated.size)
        assertEquals(
            setOf(a.key, b.key),
            concatenated.merge(fixture.item).keys(),
        )
    }

    @Test
    fun `abstract and concrete fields merge after concrete default specialization`() {
        val fixture = Fixture()
        val abstract =
            fixture.selection(
                typeName = "Item",
                fieldName = "computed",
                possibleTypes = setOf(fixture.item),
            )
        val concrete =
            fixture.selection(
                typeName = "ConcreteItem",
                fieldName = "computed",
                arguments = mapOf("factor" to 7),
            )

        val merged = selectionForestOf(abstract, concrete).merge(fixture.item)

        assertEquals(1, merged.size)
        assertEquals(
            ObjectEngineResult.Key.of(
                fixture.schema.requireField("ConcreteItem", "computed"),
                mapOf("factor" to 7),
            ),
            merged.single().key,
        )
    }

    @Test
    fun `inapplicable occurrences contribute neither keys nor descendants`() {
        val fixture = Fixture()
        val hidden = fixture.selection("ConcreteItem", "a")
        val visible = fixture.selection("ConcreteItem", "b")
        val inapplicable =
            fixture.selection(
                typeName = "Query",
                fieldName = "item",
                possibleTypes = emptySet(),
                subselections = selectionForestOf(hidden),
            )
        val applicable =
            fixture.selection(
                typeName = "Query",
                fieldName = "item",
                subselections = selectionForestOf(visible),
            )

        val merged = selectionForestOf(inapplicable, applicable).merge(fixture.query)

        val item = merged.single()
        assertEquals(1, item.subselections.size)
        assertEquals(
            setOf(visible.key),
            item.subselections.merge(fixture.item).keys(),
        )
    }

    @Test
    fun `permuting occurrences preserves the keyed merged result`() {
        val fixture = Fixture()
        val a = fixture.selection("ConcreteItem", "a")
        val b = fixture.selection("ConcreteItem", "b")
        val first = fixture.selection("Query", "item", subselections = selectionForestOf(a))
        val second = fixture.selection("Query", "item", subselections = selectionForestOf(b))

        val forward = selectionForestOf(first, second).merge(fixture.query).single()
        val reverse = selectionForestOf(second, first).merge(fixture.query).single()

        assertEquals(forward.key, reverse.key)
        assertEquals(forward.possibleTypes, reverse.possibleTypes)
        assertEquals(forward.subselections.size, reverse.subselections.size)
        assertEquals(
            forward.subselections.merge(fixture.item).keys(),
            reverse.subselections.merge(fixture.item).keys(),
        )
    }

    @Test
    fun `merge coalesces equal open object keys`() {
        val fixture = Fixture()
        val variableField = fixture.schema.requireObjectField("Query", "search")
        val sameX =
            fixture.searchSelection(
                mapOf(
                    "values" to
                        listOf(Arguments.Variable.of(variableField, "x")),
                ),
            )
        val anotherX =
            fixture.searchSelection(
                mapOf(
                    "values" to
                        listOf(Arguments.Variable.of(variableField, "x")),
                ),
            )
        val y =
            fixture.searchSelection(
                mapOf(
                    "values" to
                        listOf(Arguments.Variable.of(variableField, "y")),
                ),
            )
        val literal =
            fixture.searchSelection(
                mapOf("values" to listOf(1)),
            )

        val merged =
            selectionForestOf(sameX, anotherX, y, literal).merge(fixture.query)

        assertEquals(3, merged.size)
        merged.forEach { selection ->
            assertIs<ObjectSelection>(selection)
        }
        assertFailsWith<IllegalStateException> {
            merged.groundKeys()
        }
    }

    @Test
    fun `merge coalesces equal instantiated arguments and keeps distinct instances separate`() {
        val fixture = Fixture()
        val definingField = fixture.schema.requireObjectField("Query", "search")
        val firstVariable =
            Arguments.Variable.of(definingField, "value")
                .instanceAt(listOf(ListEngineResult.Index.of(0)))
        val equalFirstVariable =
            Arguments.Variable.of(definingField, "value")
                .instanceAt(listOf(ListEngineResult.Index.of(0)))
        val secondVariable =
            Arguments.Variable.of(definingField, "value")
                .instanceAt(listOf(ListEngineResult.Index.of(1)))
        val a = fixture.selection("ConcreteItem", "a")
        val b = fixture.selection("ConcreteItem", "b")

        fun itemSelection(
            variable: Arguments.Variable,
            subselections: SelectionForest,
        ): Selection =
            fixture.selection(
                typeName = "Query",
                fieldName = "find",
                arguments =
                    mapOf(
                        "filter" to
                            mapOf(
                                "values" to listOf(variable),
                            ),
                    ),
                subselections = subselections,
            )

        val first = itemSelection(firstVariable, selectionForestOf(a))
        val equalFirst = itemSelection(equalFirstVariable, selectionForestOf(b))
        val second = itemSelection(secondVariable, selectionForestOf(a))
        val merged = selectionForestOf(first, equalFirst, second).merge(fixture.query)
        val firstKey = first.objectKey(fixture.query)
        val secondKey = second.objectKey(fixture.query)

        assertEquals(firstKey, equalFirst.objectKey(fixture.query))
        assertNotEquals(firstKey, secondKey)
        assertEquals(2, merged.size)
        assertEquals(
            setOf(a.key, b.key),
            merged[firstKey].subselections.merge(fixture.item).keys(),
        )
    }

    @Test
    fun `merge disjoins equal keys and pushes each occurrence condition into its children`() {
        val fixture = Fixture()
        val variableField = fixture.schema.requireObjectField("Query", "item")
        val x = Arguments.Variable.of(variableField, "x")
        val y = Arguments.Variable.of(variableField, "y")
        val xCondition = InclusionCondition.requires(mapOf(x to true))
        val yCondition = InclusionCondition.requires(mapOf(y to true))
        val first =
            fixture.selection(
                "Query",
                "item",
                subselections = selectionForestOf(fixture.selection("ConcreteItem", "a")),
                inclusionCondition = xCondition,
            )
        val second =
            fixture.selection(
                "Query",
                "item",
                subselections = selectionForestOf(fixture.selection("ConcreteItem", "b")),
                inclusionCondition = yCondition,
            )

        val item = selectionForestOf(first, second).merge(fixture.query).single()
        val children = item.subselections.merge(fixture.item).byKey().values
        val a = children.single { selection -> selection.key.field.name == "a" }
        val b = children.single { selection -> selection.key.field.name == "b" }

        assertTrue(item.inclusionCondition.include(mapOf(x to true, y to false)))
        assertTrue(item.inclusionCondition.include(mapOf(x to false, y to true)))
        assertFalse(item.inclusionCondition.include(mapOf(x to false, y to false)))
        assertTrue(a.inclusionCondition.include(mapOf(x to true, y to false)))
        assertFalse(a.inclusionCondition.include(mapOf(x to false, y to true)))
        assertFalse(b.inclusionCondition.include(mapOf(x to true, y to false)))
        assertTrue(b.inclusionCondition.include(mapOf(x to false, y to true)))
    }

    @Test
    fun `conditional singleton pushes its guard into independently guarded descendants`() {
        val fixture = Fixture()
        val field = fixture.schema.requireObjectField("Query", "item")
        val parentVariable = Arguments.Variable.of(field, "parent")
        val childVariable = Arguments.Variable.of(field, "child")
        val parentCondition = InclusionCondition.requires(mapOf(parentVariable to true))
        val childCondition = InclusionCondition.requires(mapOf(childVariable to true))
        val child = fixture.selection("ConcreteItem", "a", inclusionCondition = childCondition)
        val parent = fixture.selection("Query", "item", subselections = selectionForestOf(child), inclusionCondition = parentCondition)

        val merged = selectionForestOf(parent).merge(fixture.query).single()
        val mergedChild = merged.subselections.merge(fixture.item).single()

        for (parentIncluded in listOf(false, true)) {
            for (childIncluded in listOf(false, true)) {
                val bindings = mapOf(parentVariable to parentIncluded, childVariable to childIncluded)
                assertEquals(parentIncluded, merged.inclusionCondition.include(bindings))
                assertEquals(parentIncluded && childIncluded, mergedChild.inclusionCondition.include(bindings))
            }
        }
        assertTrue(child.inclusionCondition.include(mapOf(childVariable to true)))
    }

    @Test
    fun `merging a directly constructed object forest propagates the singleton guard`() {
        val fixture = Fixture()
        val field = fixture.schema.requireObjectField("Query", "item")
        val variable = Arguments.Variable.of(field, "included")
        val condition = InclusionCondition.requires(mapOf(variable to true))
        val child = fixture.selection("ConcreteItem", "a")
        val parent = assertIs<ObjectSelection>(
            fixture.selection("Query", "item", subselections = selectionForestOf(child), inclusionCondition = condition),
        )
        val forest = ObjectSelectionForest.of(fixture.query, listOf(parent))

        val mergedChild = forest.merge(fixture.query).single().subselections.merge(fixture.item).single()

        assertFalse(mergedChild.inclusionCondition.include(mapOf(variable to false)))
        assertTrue(mergedChild.inclusionCondition.include(mapOf(variable to true)))
        assertSame(InclusionCondition.Always, child.inclusionCondition)
    }

    @Test
    fun `abstract singleton applies concrete defaults and exclusive applicability`() {
        val fixture = Fixture()
        val abstract = fixture.selection("Item", "computed")

        val merged = selectionForestOf(abstract).merge(fixture.item).single()

        assertEquals(fixture.schema.requireObjectField("ConcreteItem", "computed"), merged.key.field)
        assertEquals(
            ObjectEngineResult.GroundKey.of(fixture.schema.requireObjectField("ConcreteItem", "computed"), mapOf("factor" to 7)),
            merged.key,
        )
        assertEquals(setOf(fixture.item), merged.possibleTypes)
    }

    @Test
    fun `singleton and duplicate groups retain guard failure order`() {
        val fixture = Fixture()
        val field = fixture.schema.requireObjectField("Query", "item")
        val x = Arguments.Variable.of(field, "x")
        val y = Arguments.Variable.of(field, "y")
        val z = Arguments.Variable.of(field, "z")
        val firstCondition = InclusionCondition.requires(linkedMapOf(z to true, x to true))
        val first = fixture.selection("Query", "item", inclusionCondition = firstCondition)
        val second = fixture.selection("Query", "item", inclusionCondition = InclusionCondition.requires(mapOf(y to true)))
        val singleton = fixture.selection("Query", "optionalItem", inclusionCondition = firstCondition)
        val merged = selectionForestOf(first, singleton, second, first).merge(fixture.query)
        val visited = mutableListOf<Arguments.Variable>()

        fun binding(variable: Arguments.Variable): Boolean {
            visited += variable
            check(variable != x) { "Excluded demand read x" }
            return variable == y
        }

        assertTrue(merged[first.objectKey(fixture.query)].inclusionCondition.includeWith(::binding))
        assertEquals(listOf(z, y), visited)
        visited.clear()
        assertFalse(merged[singleton.objectKey(fixture.query)].inclusionCondition.includeWith(::binding))
        assertEquals(listOf(z), visited)
        visited.clear()
        assertFailsWith<IllegalStateException> {
            merged[first.objectKey(fixture.query)].inclusionCondition.includeWith { variable ->
                visited += variable
                if (variable == z) false else error("Rejected alternative failed")
            }
        }
        assertEquals(listOf(z, y), visited)
    }

    @Test
    fun `guarding preserves statically excluded occurrences as metadata`() {
        val fixture = Fixture()
        val selection = fixture.selection("Query", "scalar", arguments = mapOf("arg" to 1))

        val guarded = selectionForestOf(selection).guardedBy(InclusionCondition.Never)

        assertEquals(1, guarded.size)
        assertSame(InclusionCondition.Never, guarded.single().inclusionCondition)
    }

    private class Fixture {
        val testWorld = TestWorld.fromSDL(SCHEMA)
        val world = testWorld.assumptions
        val schema = world.schema
        val query = schema.requireQueryTypeDef()
        val item = schema.requireType("ConcreteItem") as ViaductSchema.Object
        val filter = schema.requireType("Filter") as ViaductSchema.Input

        fun selection(
            typeName: String,
            fieldName: String,
            arguments: Map<String, Any?> = emptyMap(),
            possibleTypes: Set<ViaductSchema.Object> =
                (schema.requireType(typeName) as ViaductSchema.CompositeTypeDef).possibleObjectTypes,
            subselections: SelectionForest = selectionForestOf(),
            inclusionCondition: InclusionCondition = InclusionCondition.Always,
        ): Selection =
            Selection.of(
                key = ObjectEngineResult.Key.of(schema.requireField(typeName, fieldName), arguments),
                possibleTypes = possibleTypes,
                subselections = subselections,
                inclusionCondition = inclusionCondition,
            )

        fun searchSelection(filter: Any): Selection =
            selection(
                typeName = "Query",
                fieldName = "search",
                arguments = mapOf("filter" to filter),
            )
    }

    private companion object {
        val SCHEMA =
            """
            input Filter {
              values: [Int!]!
            }

            interface Item {
              computed: Int!
            }

            type ConcreteItem implements Item {
              computed(factor: Int = 7): Int!
              a: Int!
              b: Int!
            }

            type Query {
              scalar(arg: Int!): Int!
              item: ConcreteItem!
              optionalItem: ConcreteItem
              search(filter: Filter!): Int!
              find(filter: Filter!): ConcreteItem!
              source(values: [Int!]!): Int!
              nested(values: [[Int]]): Int!
            }
            """.trimIndent()
    }
}

private fun Arguments.Variable.instanceAt(path: List<PathComponent>): Arguments.Variable =
    instantiate(
        ResolverOccurrenceId.at((target as ResolverTarget.FieldTarget).field.testRoot(), path),
    )
