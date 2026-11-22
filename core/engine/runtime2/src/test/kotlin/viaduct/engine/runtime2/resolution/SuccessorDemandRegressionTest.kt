package viaduct.engine.runtime2.resolution

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import viaduct.engine.api.CheckerResult
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.Selection
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.materializeSelectionForestOf
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.model.registry.FieldCheckerResolver
import viaduct.engine.runtime2.model.registry.ResolverFragmentTemplates
import viaduct.engine.runtime2.model.registry.ResolverRegistry
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.toSelectionForest
import viaduct.engine.runtime2.resolution.framework.ResolutionObserver
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.graphql.schema.ViaductSchema

class SuccessorDemandRegressionTest : ResolutionDispatcherResource {
    @Test
    fun `wide specialized successor demand avoids repeated whole forest normalization`() {
        val count = 1259
        val fixture = TestWorld.fromDSL(
            "extend type Query { item: Item } interface Item { text: String } " +
                (1..count).joinToString(" ") { "type Item$it implements Item { text: String }" },
        )
        val type = fixture.schema.requireType("Item") as ViaductSchema.CompositeTypeDef
        val requested = type.possibleObjectTypes.map { concrete ->
            val field = requireNotNull(concrete.field("text"))
            Selection.of(
                ObjectEngineResult.ObjectKey.of(field, Arguments.Resolved.of(field, emptyMap())),
                setOf(concrete),
                selectionForestOf(),
            )
        }.toSelectionForest()
        val allocations = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assertTrue(allocations.isThreadAllocatedMemorySupported, "The allocation regression requires JVM thread allocation counters")
        val wasEnabled = allocations.isThreadAllocatedMemoryEnabled
        allocations.isThreadAllocatedMemoryEnabled = true
        @Suppress("DEPRECATION")
        val threadId = Thread.currentThread().id
        try {
            val allocated = (1..3).minOf {
                val before = allocations.getThreadAllocatedBytes(threadId)
                val actual = requested.successorDemand(fixture.assumptions, engineExecutionContext = null)
                val bytes = allocations.getThreadAllocatedBytes(threadId) - before
                assertEquals(count, actual.size)
                assertEquals(type.possibleObjectTypes.map { it.name }.toSet(), buildSet { actual.forEach { add(it.key.field.containingDef.name) } })
                actual.forEach { selection ->
                    assertEquals("text", selection.key.field.name)
                    assertEquals(setOf(selection.key.field.containingDef), selection.possibleTypes)
                    assertTrue(selection.subselections.isEmpty())
                }
                bytes
            }
            assertTrue(allocated < 8L * 1024 * 1024, "Wide specialized successor demand allocated $allocated bytes")
        } finally {
            allocations.isThreadAllocatedMemoryEnabled = wasEnabled
        }
    }

    @Test
    fun `successor compaction preserves shape and guards across fresh caches`() {
        val fixture = TestWorld.fromDSL(
            "extend type Query { item: Item } interface Item { child: Item text: String } " +
                (1..3).joinToString(" ") { "type Item$it implements Item { child: Item text: String }" },
        )
        val types = (fixture.schema.requireType("Item") as ViaductSchema.CompositeTypeDef).possibleObjectTypes
        val child = fixture.schemas.fragmentFrom("fragment F on Item { child { text } }").subselections.single()
        val text = child.subselections.single()
        val field = fixture.schema.requireObjectField("Query", "item")
        val firstVariable = Arguments.Variable.of(field, "first")
        val secondVariable = Arguments.Variable.of(field, "second")
        val first = InclusionCondition.requires(mapOf(firstVariable to true))
        val second = InclusionCondition.requires(mapOf(secondVariable to true))
        val guards = listOf(InclusionCondition.Always, InclusionCondition.Never, first, second, first.or(second), first.and(second), InclusionCondition.requires(mapOf(firstVariable to false)))
        val cases = mutableListOf(selectionForestOf(), selectionForestOf(text), selectionForestOf(child))
        guards.forEach { left ->
            guards.forEach { right ->
                val children = selectionForestOf(
                    text,
                    Selection.of(text.key, types.take(2).toSet(), selectionForestOf(), right),
                )
                val shared = Selection.of(child.key, types, children, left)
                cases += selectionForestOf(
                    shared,
                    shared,
                    Selection.of(child.key, types.drop(1).toSet(), child.subselections, right),
                    text,
                )
            }
        }
        cases.forEachIndexed { index, input ->
            val once = SuccessorDemandCompactionCache().compact(input)
            val twice = SuccessorDemandCompactionCache().compact(once)
            for (firstValue in listOf(false, true)) {
                for (secondValue in listOf(false, true)) {
                    val bindings = mapOf(firstVariable to firstValue, secondVariable to secondValue)
                    assertEquals(once.compactedShape(bindings), twice.compactedShape(bindings), "Compaction changed on the second pass for case $index with $bindings")
                }
            }
        }
    }

    @Test
    fun `overlapping polymorphic fragments retain shared compacted descendants`() {
        val depth = 6
        val fragments = (0 until depth).joinToString("\n") { level ->
            "fragment Part$level on Item { child { ...Part${level + 1} } child { ...Part${level + 1} } }"
        } + "\nfragment Part$depth on Item { text }"
        val fixture = TestWorld.fromDSL(
            "extend type Query { item: Item output: Int } interface Item { child: Item text: String } " +
                (1..3).joinToString(" ") { "type Item$it implements Item { child: Item text: String }" },
            fieldCheckers = { schema ->
                val output = schema.loweredSchema.requireObjectField("Query", "output")
                val inputs = schema.fragmentFrom("fragment Main on Query { item { ...Part0 } }\n$fragments").materializeSelections
                mapOf(
                    output to FieldCheckerResolver.of(
                        output,
                        schema.loweredSchema.requireQueryTypeDef(),
                        mapOf("inputs" to ResolverFragmentTemplates(inputs, materializeSelectionForestOf())),
                    ) { _, _, _ -> CheckerResult.Success }
                )
            },
        )
        for (requested in listOf("item { ...Part0 }", "output")) {
            val demand = fixture.schemas.fragmentFrom("fragment Main on Query { $requested }\n$fragments")
                .subselections.successorDemand(fixture.assumptions, engineExecutionContext = null)
            val visited = Collections.newSetFromMap(IdentityHashMap<SelectionForest, Boolean>())

            fun visit(
                forest: SelectionForest,
                remaining: Int
            ) {
                if (!visited.add(forest)) return
                assertEquals(setOf("Item1", "Item2", "Item3"), buildSet { forest.forEach { add(it.key.field.containingDef.name) } })
                forest.forEach { selection ->
                    assertEquals(if (remaining == 0) "text" else "child", selection.key.field.name)
                    if (remaining == 0) assertTrue(selection.subselections.isEmpty()) else visit(selection.subselections, remaining - 1)
                }
            }
            visit(demand.filter { it.key.field.name == "item" }.single().subselections, depth)
            assertTrue(visited.size <= 4 * (depth + 1), "Overlapping fragments in $requested produced ${visited.size} distinct demand forests")
        }
    }

    @Test
    fun `polymorphic descendants are expanded once per shared forest`() {
        val depth = 6
        val source = "child { ".repeat(depth) + "text" + " }".repeat(depth)
        val fixture = TestWorld.fromDSL(
            "extend type Query { item: Item output: Int @resolver(of: \"item { $source }\", script: \"1\") } interface Item { child: Item text: String } " +
                (1..3).joinToString(" ") { "type Item$it implements Item { child: Item text: String }" },
        )
        val original = fixture.assumptions
        var lookups = 0
        val registry = object : ResolverRegistry by original.resolverRegistry {
            override fun contains(field: ViaductSchema.ObjectField): Boolean {
                lookups++
                return field in original.resolverRegistry
            }
        }
        val world = Assumptions.of(original.schema, registry)

        fun assertShape(
            forest: SelectionForest,
            remaining: Int
        ) {
            assertEquals(3, forest.size)
            assertEquals(setOf("Item1", "Item2", "Item3"), buildSet { forest.forEach { add(it.key.field.containingDef.name) } })
            forest.forEach { selection ->
                assertEquals(if (remaining == 0) "text" else "child", selection.key.field.name)
                if (remaining == 0) assertTrue(selection.subselections.isEmpty()) else assertShape(selection.subselections, remaining - 1)
            }
        }
        for (requested in listOf("item { $source }", "output")) {
            lookups = 0
            val actual = fixture.schemas.fragmentFrom("fragment Output on Query { $requested }")
                .subselections.successorDemand(world, engineExecutionContext = null)
            val item = actual.filter { it.key.field.name == "item" }.single()
            assertShape(item.subselections, depth)
            assertTrue(lookups <= 10 * 3 * (depth + 1), "Polymorphic descendants of $requested required $lookups registry lookups")
        }
    }

    @Test
    fun `shared resolver and checker dependencies stay bounded across a diamond`() {
        val depth = 12

        fun inputs(index: Int) = ((index + 1)..minOf(index + 2, depth)).joinToString(" ") { "field$it" }
        val fields = (0..depth).joinToString("\n") { index ->
            val checker = if (index == depth) {
                ""
            } else {
                """@checker(inputs: [{name: "first", of: "${inputs(index)}"}, {name: "second", of: "${inputs(index)}"}], script: "allow")"""
            }
            """field$index: Int @resolver(of: "${inputs(index)}", script: "1") $checker"""
        }
        val originalFixture = TestWorld.fromDSL("extend type Query { $fields }")
        val original = originalFixture.assumptions
        var lookups = 0
        val registry = object : ResolverRegistry by original.resolverRegistry {
            override fun resolver(field: ViaductSchema.ObjectField) = original.resolverRegistry.resolver(field).also { lookups++ }

            override fun fieldChecker(field: ViaductSchema.ObjectField) = original.resolverRegistry.fieldChecker(field).also { lookups++ }
        }
        val world = Assumptions.of(original.schema, registry)
        val actual = originalFixture.schemas
            .fragmentFrom("fragment Output on Query { field0 }")
            .subselections
            .successorDemand(world, engineExecutionContext = null)
        assertEquals(
            (0..depth).map { "field$it" }.toSet(),
            actual
                .merge(world.schema.requireQueryTypeDef())
                .keys()
                .map { it.field.name }
                .toSet()
        )
        assertTrue(lookups <= 4 * (depth + 1) * (depth + 1), "Shared dependency DAG required $lookups registry lookups")
        assertTrue(actual.size <= 2 * (depth + 1), "Duplicate input pairs expanded to ${actual.size} producer selections")
    }

    @Test
    fun `excluded child does not demand parent-derived value unconditionally`() {
        val fixture = TestWorld.fromDSL(
            """
            extend type Query { root: Root @resolver(script: "{:}") }
            type Root { seed: Int child: Child }
            type Child {
              parent: Root @parent
              derived: Int @resolver(of: "parent { seed }", script: "1")
            }
            """.trimIndent(),
        )
        val root = fixture.schema.requireObjectField("Root", "seed").containingDef
        val input = fixture.schemas.fragmentFrom(
            "fragment F on Root { child @skip(if: true) { derived } }",
        ).subselections
        val output = input.successorDemand(fixture.assumptions, engineExecutionContext = null).merge(root)
        val seed = output.byKey().values.find { it.key.field.name == "seed" }
        assertTrue(
            seed == null || seed.inclusionCondition === InclusionCondition.Never,
            "Excluded child produced seed with condition ${seed?.inclusionCondition}",
        )
    }

    @Test
    fun `publication preserves exclusion on parent-derived producer demand`() {
        val fixture = TestWorld.fromDSL(
            """
            extend type Query { root: Root @resolver(script: "{'seed': 7, 'child': {:}}") }
            type Root { seed: Int child: Child }
            type Child {
              parent: Root @parent
              derived: Int @resolver(of: "parent { seed }", script: "1")
            }
            """.trimIndent(),
        )
        val observations = mutableListOf<ResolverInvocationObservation>()
        val observer = object : ResolutionObserver {
            override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
                observations += observation
            }
        }
        val operation = SharedOperationContext.create(fixture.assumptions, resolutionObserver = observer)
        operation.resolveWithTestDispatcher(
            fixture.schemas.fragmentFrom(
                "fragment F on Query { root { child @skip(if: true) { derived } } }",
            ).subselections,
        )
        val root = fixture.schema.requireObjectField("Root", "seed").containingDef
        val rootDemand = requireNotNull(
            observations.single { it.field.name == "root" }.suppliedDemand,
        ).merge(root)
        val seed = rootDemand.byKey().values.find { it.key.field.name == "seed" }
        assertTrue(
            seed == null || seed.inclusionCondition === InclusionCondition.Never,
            "Publication demanded seed with condition ${seed?.inclusionCondition}",
        )
    }
}

private data class CompactedSelectionShape(
    val key: ObjectEngineResult.Key,
    val possibleTypes: Set<ViaductSchema.Object>,
    val included: Boolean,
    val children: Map<CompactedSelectionShape, Int>,
)

private fun SelectionForest.compactedShape(bindings: Map<Arguments.Variable, Boolean>): Map<CompactedSelectionShape, Int> =
    buildList {
        this@compactedShape.forEach { selection ->
            add(CompactedSelectionShape(selection.key, selection.possibleTypes, selection.inclusionCondition.include(bindings), selection.subselections.compactedShape(bindings)))
        }
    }.groupingBy { it }.eachCount()
