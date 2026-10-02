package viaduct.engine.runtime.execution

import graphql.execution.CoercedVariables
import java.util.Collections
import java.util.IdentityHashMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.runtime.HasResolver
import viaduct.engine.runtime.mat.KeyTree
import viaduct.engine.runtime.mat.KeyTreeFilter
import viaduct.engine.runtime.result.ObjectEngineResult

class PolymorphicKeyTreeTest {
    @Test
    fun `deep wide polymorphic coverage and projection stay shared`() {
        val fixture = PolymorphicKeyTreeFixture(depth = 7, sectionTypes = 916, merged = true)
        val first = fixture.coverage()
        val second = fixture.coverage(value = 9)
        fixture.assertCoverage(first, depth = 7)
        assertTrue(uniqueTrees(first) <= 24)
        fixture.assertCoverage(first + first, depth = 7)
        assertTrue((first - first).isEmpty())

        val union = first + second
        assertTrue(uniqueTrees(union) <= 24)
        fixture.assertCoverage(union - second, depth = 7, checkContainers = false)
        fixture.assertCoverage(union.intersect(first), depth = 7)
        assertTrue(first == fixture.coverage())
        assertEquals(first.hashCode(), fixture.coverage().hashCode())
        assertTrue(first.toString().length < 20000)

        val parameters = fixture.parameters
        val source = checkNotNull(parameters.field?.selectionSet)
        val projected = parameters.queryPlan.filterTo(first, QueryPlanFilterCtx(parameters), source)
        val rebuilt = projected.keyTree(parameters, projected.selectionSet).filter(FieldOutputSelectionSetFilter(HasResolver.Never)).withoutEmptyTypeBranches()
        fixture.assertCoverage(rebuilt, depth = 7)
        assertTrue(first == rebuilt)
        assertEquals(setOf("value", "includeText"), projected.variableDefinitions.map { it.name }.toSet())
        assertTrue(uniqueSelectionSets(projected.selectionSet) < 120)
        val ast = projected.selectionSet.toAstSelectionSet()
        assertTrue(ast.selections.isNotEmpty())
    }

    @Test
    fun `filter visits shared descendants once and distinguishes the root`() {
        val fixture = PolymorphicKeyTreeFixture(depth = 7, sectionTypes = 8)
        val tree = fixture.coverage()
        var visits = 0
        val filtered = tree.filter(
            KeyTreeFilter { _, _, topLevel ->
                visits++
                if (topLevel) KeyTreeFilter.Result.KEEP_AND_RECURSE else KeyTreeFilter.Result.KEEP_WITHOUT_CHILDREN
            }
        ).withoutEmptyTypeBranches()
        assertEquals(11, visits)
        assertEquals(3, uniqueTrees(filtered))
    }

    @Test
    fun `nested polymorphic coverage shares equivalent descendants`() {
        val fixture = PolymorphicKeyTreeFixture(depth = 3, sectionTypes = 24)
        val tree = fixture.coverage()

        fixture.assertCoverage(tree, depth = 3)
        assertTrue(uniqueTrees(tree) <= 12, "Coverage size must follow selection depth, not concrete-type ancestry combinations")
    }

    @Test
    fun `coverage construction does not reuse another invocation's variables`() {
        val fixture = PolymorphicKeyTreeFixture(depth = 2, sectionTypes = 8)
        val first = fixture.coverage()
        val second = fixture.coverage(value = 9)
        val excluded = fixture.coverage(includeText = false)

        fixture.assertCoverage(first, depth = 2, value = 7)
        fixture.assertCoverage(second, depth = 2, value = 9)
        fixture.assertCoverage(excluded, depth = 2, includeText = false)
    }

    private fun uniqueTrees(root: KeyTree): Int {
        val seen = Collections.newSetFromMap(IdentityHashMap<KeyTree, Boolean>())

        fun visit(tree: KeyTree) {
            if (!seen.add(tree)) return
            tree.keysByType().values.forEach { fields -> fields.values.forEach(::visit) }
        }
        visit(root)
        return seen.size
    }

    private fun uniqueSelectionSets(root: QueryPlan.SelectionSet): Int {
        val seen = Collections.newSetFromMap(IdentityHashMap<QueryPlan.SelectionSet, Boolean>())

        fun visit(set: QueryPlan.SelectionSet) {
            if (!seen.add(set)) return
            set.selections.forEach {
                when (it) {
                    is QueryPlan.Field -> it.selectionSet?.let(::visit)
                    is QueryPlan.InlineFragment -> visit(it.selectionSet)
                    is QueryPlan.FragmentSpread -> error("Unexpected fragment spread")
                }
            }
        }
        visit(root)
        return seen.size
    }
}

class PolymorphicKeyTreeFixture(
    depth: Int,
    sectionTypes: Int,
    merged: Boolean = false,
) {
    internal val parameters = mkExecutionParameters(
        buildString {
            appendLine("extend type Query { article: Article }")
            appendLine("type Article { sections: [ISectionDataContainer] }")
            appendLine("interface ISectionDataContainer { sectionId: String, sectionData: SectionV2 }")
            repeat(5) { appendLine("type Container$it implements ISectionDataContainer { sectionId: String, sectionData: SectionV2 }") }
            appendLine("type NestedSection { sections: [ISectionDataContainer] }")
            appendLine("type TextSection { text(n: Int): String }")
            repeat(sectionTypes - 2) { appendLine("type UnusedSection$it { unused: String }") }
            append("union SectionV2 = NestedSection | TextSection")
            repeat(sectionTypes - 2) { append(" | UnusedSection$it") }
        },
        "Query" to "article",
        buildString {
            appendLine("query PolymorphicCoverage(${'$'}includeText: Boolean! = true, ${'$'}value: Int! = 7) {")
            appendLine("  article { sections { ...ContainerSelection0 } }")
            appendLine("}")
            repeat(depth) { level ->
                appendLine("fragment ContainerSelection$level on ISectionDataContainer {")
                appendLine("  __typename sectionId sectionData {")
                appendLine("    __typename ... on TextSection { value: text(n: ${'$'}value) @include(if: ${'$'}includeText) }")
                if (level + 1 < depth) {
                    appendLine("    ... on NestedSection { sections { ...ContainerSelection${level + 1} } }")
                }
                appendLine("  }")
                if (merged) appendLine("  sectionData { __typename }")
                appendLine("}")
            }
        },
    )
    private val schema = parameters.graphQLSchema
    private val outputFilter = FieldOutputSelectionSetFilter(HasResolver.Never)

    fun coverage(
        value: Int = 7,
        includeText: Boolean = true
    ): KeyTree =
        parameters.queryPlan.keyTree(
            parameters.copy(coercedVariables = CoercedVariables.of(mapOf("value" to value, "includeText" to includeText))),
            checkNotNull(parameters.field),
            outputFilter,
        )

    fun assertCoverage(
        tree: KeyTree,
        depth: Int,
        value: Int = 7,
        includeText: Boolean = true,
        checkContainers: Boolean = true
    ) {
        var containers = tree.subtreeForKey(schema.getObjectType("Article"), ObjectEngineResult.Key("sections"))
        repeat(depth) { level ->
            assertEquals(5, containers.keysByType().size)
            val children = (0 until 5).map { index ->
                val type = schema.getObjectType("Container$index")
                assertEquals(if (checkContainers) setOf("sectionId", "sectionData") else setOf("sectionData"), containers.responseKeysForType(type))
                containers.subtreeForKey(type, ObjectEngineResult.Key("sectionData"))
            }
            assertTrue(children.all { it === children.first() }, "Equivalent sectionData selections must share their subtree at depth $level")
            val section = children.first()
            val text = schema.getObjectType("TextSection")
            assertEquals(includeText, section.containsKey(text, ObjectEngineResult.Key("text", "value", mapOf("n" to value))))
            assertEquals(
                buildSet {
                    if (includeText) add("TextSection")
                    if (level + 1 < depth) add("NestedSection")
                },
                section.keysByType().keys.map { it.name }.toSet(),
            )
            if (level + 1 < depth) {
                containers = section.subtreeForKey(schema.getObjectType("NestedSection"), ObjectEngineResult.Key("sections"))
            }
        }
    }
}
