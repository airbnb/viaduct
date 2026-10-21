package viaduct.engine.runtime2.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.testRoot
import viaduct.graphql.schema.ViaductSchema

class SelectionMergeTest {
    @Test
    fun `flat merge coalesces shared keys and preserves distinct arguments and children`() {
        val fixture = Fixture()
        val a = fixture.selection("ConcreteItem", "a")
        val b = fixture.selection("ConcreteItem", "b")
        val first =
            selectionForestOf(
                fixture.selection("Query", "scalar", mapOf("arg" to 1)),
                fixture.selection("Query", "item", subselections = selectionForestOf(a)),
            ).merge(fixture.query)
        val second =
            selectionForestOf(
                fixture.selection("Query", "scalar", mapOf("arg" to 1)),
                fixture.selection("Query", "scalar", mapOf("arg" to 2)),
                fixture.selection("Query", "item", subselections = selectionForestOf(b)),
            ).merge(fixture.query)

        val merged = listOf(first, second).flatMerge(fixture.query)

        assertEquals(3, merged.size)
        assertEquals((first + second).merge(fixture.query).keys(), merged.keys())
        assertEquals(
            setOf(a.key, b.key),
            merged.byKey().values.single { it.key.field.name == "item" }.subselections.merge(fixture.item).keys(),
        )
        assertTrue(emptyList<ObjectSelectionForest>().flatMerge(fixture.query).isEmpty())
        assertThrows<IllegalArgumentException> {
            listOf(first, selectionForestOf(a).merge(fixture.item)).flatMerge(fixture.query)
        }
    }

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

        assertInstanceOf(ObjectSelection::class.java, concrete)
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
        assertThrows<NoSuchElementException> { merged[missingKey] }
        val filtered: ObjectSelectionForest =
            merged.filter { selection -> selection.key == oneKey }
        assertEquals(setOf(oneKey), filtered.keys())
        merged.forEach { selection ->
            assertInstanceOf(ObjectSelection::class.java, selection)
            assertEquals(fixture.query, selection.key.field.containingDef)
            assertEquals(setOf(fixture.query), selection.possibleTypes)
            assertTrue(selection.subselections.isEmpty())
        }
    }

    @Test
    fun `object selection forest validates parent type exclusive applicability and unique keys`() {
        val fixture = Fixture()
        val querySelection =
            assertInstanceOf(ObjectSelection::class.java, fixture.selection("Query", "item"))
        val itemSelection =
            assertInstanceOf(ObjectSelection::class.java, fixture.selection("ConcreteItem", "a"))
        val inapplicableQuerySelection =
            ObjectSelection.of(
                key = querySelection.key,
                possibleTypes = emptySet(),
                subselections = querySelection.subselections,
            )

        assertThrows<IllegalArgumentException> {
            ObjectSelectionForest.of(fixture.query, listOf(itemSelection))
        }
        assertThrows<IllegalArgumentException> {
            ObjectSelectionForest.of(fixture.query, listOf(inapplicableQuerySelection))
        }
        assertThrows<IllegalArgumentException> {
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

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 3, 4, 6, 7, 12, 13, 25])
    fun `merged child forests preserve occurrences across growth and transformations`(count: Int) {
        val fixture = Fixture()
        val field = fixture.schema.requireObjectField("Query", "item")
        val children = (0 until count).map { index ->
            fixture.selection(
                "ConcreteItem",
                if (index % 2 == 0) "a" else "b",
                inclusionCondition = InclusionCondition.requires(mapOf(Arguments.Variable.of(field, "child$index") to true)),
            )
        }
        val first = fixture.selection("Query", "item", subselections = children.take(count / 2).toSelectionForest())
        val second = fixture.selection("Query", "item", subselections = children.drop(count / 2).toSelectionForest())
        val merged = selectionForestOf(first, second).merge(fixture.query).single().subselections
        val expected = children.map { it.key to it.inclusionCondition }.toSet()
        val observed = mutableListOf<Selection>()
        merged.forEach(observed::add)

        assertEquals(count, merged.size)
        assertEquals(count == 0, merged.isEmpty())
        assertEquals(expected, observed.map { it.key to it.inclusionCondition }.toSet())
        assertTrue(merged.all { it.key.field.containingDef == fixture.item })
        var visits = 0
        assertEquals(
            count == 0,
            merged.all {
                visits++
                false
            },
        )
        assertEquals(if (count == 0) 0 else 1, visits)
        assertEquals((count + 1) / 2, merged.filter { it.key.field.name == "a" }.size)
        assertEquals(count * 2, merged.flatMap { selectionForestOf(it, it) }.size)
        assertEquals(count, merged.mapSelections { it }.size)
        assertEquals(count + 1, (merged + selectionForestOf(fixture.selection("ConcreteItem", "a"))).size)
        if (count == 0) {
            assertThrows<NoSuchElementException> { merged.single() }
        } else if (count == 1) {
            assertEquals(children.single().key, merged.single().key)
            assertEquals(children.single().inclusionCondition, merged.single().inclusionCondition)
        } else {
            assertThrows<IllegalArgumentException> { merged.single() }
        }
    }

    @Test
    fun `merged simple outputs including wrapped types use the shared empty forest`() {
        val world = TestWorld.fromSDL("type Query { scalar: Int, values: [Int!]! }")
        val query = world.assumptions.schema.requireQueryTypeDef()
        val empty = selectionForestOf()
        val selections = listOf("scalar", "values").map { field ->
            Selection.of(ObjectEngineResult.Key.of(query.requireField(field), emptyMap()), setOf(query), empty)
        }

        val merged = (selections + selections).toSelectionForest().merge(query)

        assertEquals(2, merged.size)
        merged.forEach { assertSame(empty, it.subselections) }
    }

    @Test
    fun `merged children retain the forest member limit`() {
        val fixture = Fixture()
        val child = fixture.selection("ConcreteItem", "a")
        val first = fixture.selection(
            "Query",
            "item",
            subselections = List(SelectionForest.MAX_SIZE - 1) { child }.toSelectionForest(),
        )
        val second = fixture.selection("Query", "item", subselections = selectionForestOf(child))

        assertEquals(SelectionForest.MAX_SIZE, selectionForestOf(first, second).merge(fixture.query).single().subselections.size)
        assertThrows<IllegalArgumentException> { selectionForestOf(first, second, second).merge(fixture.query) }
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

        listOf(selectionForestOf(abstract, concrete), selectionForestOf(abstract)).forEach { forest ->
            val merged = forest.merge(fixture.item)
            assertEquals(1, merged.size)
            assertEquals(
                ObjectEngineResult.Key.of(
                    fixture.schema.requireField("ConcreteItem", "computed"),
                    mapOf("factor" to 7),
                ),
                merged.single().key,
            )
        }
    }

    @Test
    fun `merging empty input retains the concrete target type`() {
        val fixture = Fixture()
        val merged = selectionForestOf().merge(fixture.query)

        assertEquals(fixture.query, merged.type)
        assertEquals(0, merged.size)
        assertTrue(merged.byKey().isEmpty())
        assertTrue(merged.groundKeys().isEmpty())
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
        assertTrue(selectionForestOf(inapplicable).merge(fixture.query).isEmpty())
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
            assertInstanceOf(ObjectSelection::class.java, selection)
        }
        assertThrows<IllegalStateException> {
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

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `merging a directly constructed object forest propagates the singleton guard`(flat: Boolean) {
        val fixture = Fixture()
        val field = fixture.schema.requireObjectField("Query", "item")
        val variable = Arguments.Variable.of(field, "included")
        val condition = InclusionCondition.requires(mapOf(variable to true))
        val child = fixture.selection("ConcreteItem", "a")
        val parent = assertInstanceOf(
            ObjectSelection::class.java,
            fixture.selection("Query", "item", subselections = selectionForestOf(child), inclusionCondition = condition),
        )
        val forest = ObjectSelectionForest.of(fixture.query, listOf(parent))

        val merged = if (flat) listOf(forest).flatMerge(fixture.query) else forest.merge(fixture.query)
        val mergedChild = merged.single().subselections.merge(fixture.item).single()

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
        assertThrows<IllegalStateException> {
            merged[first.objectKey(fixture.query)].inclusionCondition.includeWith { variable ->
                visited += variable
                if (variable == z) false else error("Rejected alternative failed")
            }
        }
        assertEquals(listOf(z, y), visited)
    }

    @Test
    fun `singleton groups preserve parent and child conditions`() {
        val fixture = Fixture()
        val field = fixture.schema.requireObjectField("Query", "item")
        val x = Arguments.Variable.of(field, "x")
        val y = Arguments.Variable.of(field, "y")
        val childCondition = InclusionCondition.requires(mapOf(y to true))
        val child = fixture.selection("ConcreteItem", "a", inclusionCondition = childCondition)
        val leaf = fixture.selection("Query", "scalar", arguments = mapOf("arg" to 1))

        listOf(InclusionCondition.Always, InclusionCondition.Never, InclusionCondition.requires(mapOf(x to true))).forEach { condition ->
            val parent = fixture.selection("Query", "item", subselections = selectionForestOf(child), inclusionCondition = condition)
            listOf(selectionForestOf(parent), selectionForestOf(parent, leaf, leaf)).forEach { forest ->
                val merged = forest.merge(fixture.query)[parent.objectKey(fixture.query)]
                val mergedChild = merged.subselections.single()
                listOf(false, true).forEach { xValue ->
                    listOf(false, true).forEach { yValue ->
                        val bindings = mapOf(x to xValue, y to yValue)
                        assertEquals(condition.include(bindings), merged.inclusionCondition.include(bindings))
                        assertEquals(condition.include(bindings) && childCondition.include(bindings), mergedChild.inclusionCondition.include(bindings))
                    }
                }
            }
        }
    }

    @Test
    fun `guarding preserves statically excluded occurrences as metadata`() {
        val fixture = Fixture()
        val selection = fixture.selection("Query", "scalar", arguments = mapOf("arg" to 1))

        val guarded = selectionForestOf(selection).guardedBy(InclusionCondition.Never)

        assertEquals(1, guarded.size)
        assertSame(InclusionCondition.Never, guarded.single().inclusionCondition)
    }

    @Test
    fun `later merges do not mutate a published duplicate group or its shared occurrences`() {
        val fixture = Fixture()
        val field = fixture.schema.requireObjectField("Query", "item")
        val x = Arguments.Variable.of(field, "x")
        val y = Arguments.Variable.of(field, "y")
        val first = fixture.selection(
            "Query",
            "item",
            subselections = selectionForestOf(fixture.selection("ConcreteItem", "a")),
            inclusionCondition = InclusionCondition.requires(mapOf(x to true)),
        )
        val second = fixture.selection(
            "Query",
            "item",
            subselections = selectionForestOf(fixture.selection("ConcreteItem", "b")),
            inclusionCondition = InclusionCondition.requires(mapOf(y to true)),
        )
        val published = selectionForestOf(first, second).merge(fixture.query).single()
        val originalCondition = published.inclusionCondition
        val originalChildren = published.subselections
        val unconditional = fixture.selection("Query", "item", subselections = first.subselections)
        val independent = fixture.selection("Query", "scalar", mapOf("arg" to 1))

        val later = selectionForestOf(published, independent, unconditional, published).merge(fixture.query)
        val laterItem = later[published.objectKey(fixture.query)]

        assertSame(InclusionCondition.Always, laterItem.inclusionCondition)
        assertEquals(5, laterItem.subselections.size)
        assertSame(independent, later[independent.objectKey(fixture.query)])
        assertSame(originalCondition, published.inclusionCondition)
        assertSame(originalChildren, published.subselections)
        assertEquals(2, originalChildren.size)
        assertFalse(published.inclusionCondition.include(mapOf(x to false, y to false)))
        assertEquals(1, first.subselections.size)
        assertEquals(1, second.subselections.size)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `unconditional duplicate leaves other occurrences children independently guarded`(flat: Boolean) {
        val fixture = Fixture()
        val field = fixture.schema.requireObjectField("Query", "item")
        val x = Arguments.Variable.of(field, "x")
        val y = Arguments.Variable.of(field, "y")
        val first = fixture.selection(
            "Query",
            "item",
            subselections = selectionForestOf(fixture.selection("ConcreteItem", "a")),
            inclusionCondition = InclusionCondition.requires(mapOf(x to true)),
        )
        val unconditional = fixture.selection("Query", "item")
        val last = fixture.selection(
            "Query",
            "item",
            subselections = selectionForestOf(fixture.selection("ConcreteItem", "b")),
            inclusionCondition = InclusionCondition.requires(mapOf(y to true)),
        )

        for (occurrences in listOf(listOf(first, unconditional, last), listOf(unconditional, first, last))) {
            val merged =
                if (flat) {
                    occurrences.map { selectionForestOf(it).merge(fixture.query) }.flatMerge(fixture.query)
                } else {
                    occurrences.toSelectionForest().merge(fixture.query)
                }
            val parent = merged.single()
            val children = parent.subselections.merge(fixture.item)
            val a = children[first.subselections.single().objectKey(fixture.item)]
            val b = children[last.subselections.single().objectKey(fixture.item)]
            assertSame(InclusionCondition.Always, parent.inclusionCondition)
            assertEquals(2, parent.subselections.size)
            for (xValue in listOf(false, true)) {
                for (yValue in listOf(false, true)) {
                    val bindings = mapOf(x to xValue, y to yValue)
                    assertEquals(xValue, a.inclusionCondition.include(bindings))
                    assertEquals(yValue, b.inclusionCondition.include(bindings))
                }
            }
        }
    }

    @Test
    fun `incremental duplicate children retain their condition evaluation order`() {
        val fixture = Fixture()
        val field = fixture.schema.requireObjectField("Query", "item")
        val variables = listOf("first", "second", "unread").map { Arguments.Variable.of(field, it) }
        val occurrences = variables.map { variable ->
            fixture.selection(
                "Query",
                "item",
                subselections = selectionForestOf(fixture.selection("ConcreteItem", "a")),
                inclusionCondition = InclusionCondition.requires(mapOf(variable to true)),
            )
        }
        val children = occurrences.toSelectionForest().merge(fixture.query).single().subselections
        val condition = children.merge(fixture.item).single().inclusionCondition
        val visited = mutableListOf<Arguments.Variable>()

        assertEquals(3, children.size)
        assertTrue(
            condition.includeWith { variable ->
                visited += variable
                check(variable != variables[2]) { "Short-circuited child condition was evaluated" }
                variable == variables[1]
            }
        )
        assertEquals(variables.take(2), visited)
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
