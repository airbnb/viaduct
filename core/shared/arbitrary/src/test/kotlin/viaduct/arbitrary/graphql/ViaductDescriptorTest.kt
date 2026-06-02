@file:Suppress("ForbiddenImport")

package viaduct.arbitrary.graphql

import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.next
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.CompoundingWeight.Companion.Once
import viaduct.arbitrary.common.Config
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.VariablesResolver as EngineVariablesResolver
import viaduct.engine.api.select.SelectionsParser

class ViaductDescriptorTest {
    @Test
    fun `RequiredSelectionSet descriptor includes variables resolver required selection set`() {
        val nestedRss = RequiredSelectionSet(
            selections = SelectionsParser.parse("Query", "b"),
            variablesResolvers = emptyList(),
            forChecker = false,
        )
        val variablesResolver = object : EngineVariablesResolver {
            override val variableNames: Set<String> = setOf("id")
            override val requiredSelectionSet: RequiredSelectionSet = nestedRss

            override suspend fun resolve(
                ctx: EngineVariablesResolver.ResolveCtx,
                context: EngineExecutionContext,
            ): Map<String, Any?> = emptyMap()
        }
        val rss = RequiredSelectionSet(
            selections = SelectionsParser.parse("Query", "a(id: \$id)"),
            variablesResolvers = listOf(variablesResolver),
            forChecker = false,
        )

        val dump = rss.describe()

        assertEquals("Query", dump.typeName)
        assertEquals(listOf("id"), dump.variablesResolvers.single().variableNames.sorted())
        assertEquals("Query", dump.variablesResolvers.single().requiredSelectionSet?.typeName)
        val rendered = dump.toString()
        assertTrue(rendered.contains("type: Query"))
        assertTrue(rendered.contains("variablesResolvers:"))
        assertTrue(rendered.contains("- variables: [id]"))
        assertTrue(rendered.contains("requiredSelectionSet:"))
    }

    @Test
    fun `VariablesResolver descriptor includes nested required selection set`() {
        val nestedRss = RequiredSelectionSet(
            selections = SelectionsParser.parse("Query", "b"),
            variablesResolvers = emptyList(),
            forChecker = false,
        )
        val variablesResolver = object : EngineVariablesResolver {
            override val variableNames: Set<String> = setOf("value")
            override val requiredSelectionSet: RequiredSelectionSet = nestedRss

            override suspend fun resolve(
                ctx: EngineVariablesResolver.ResolveCtx,
                context: EngineExecutionContext,
            ): Map<String, Any?> = emptyMap()
        }

        val dump = variablesResolver.describe()

        assertEquals(setOf("value"), dump.variableNames)
        assertEquals("Query", dump.requiredSelectionSet?.typeName)
        val rendered = dump.toString()
        assertTrue(rendered.contains("- variables: [value]"))
        assertTrue(rendered.contains("class:"))
        assertTrue(rendered.contains("requiredSelectionSet:"))
    }

    @Test
    fun `required selection set descriptor stops at cycles`() {
        lateinit var rss: RequiredSelectionSet
        val variablesResolver = object : EngineVariablesResolver {
            override val variableNames: Set<String> = setOf("id")
            override val requiredSelectionSet: RequiredSelectionSet
                get() = rss

            override suspend fun resolve(
                ctx: EngineVariablesResolver.ResolveCtx,
                context: EngineExecutionContext,
            ): Map<String, Any?> = emptyMap()
        }
        rss = RequiredSelectionSet(
            selections = SelectionsParser.parse("Query", "a(id: \$id)"),
            variablesResolvers = listOf(variablesResolver),
            forChecker = false,
        )

        val rendered = rss.describe().toString()

        assertTrue(rendered.contains("selections: <already described>"))
    }

    @Test
    fun `generated viaduct descriptor includes schema and resolver configuration`(): Unit =
        runBlocking {
            val schema = """
                type Foo { x: Int, y: Int }
                extend type Query { foo: Foo @resolver }
            """.asViaductSchema
            val cfg = Config.default +
                (RequiredSelectionSetWeight to Once) +
                (SelectiveResolverWeight to 1.0) +
                (FieldCheckerWeight to 0.0) +
                (TypeCheckerWeight to 0.0) +
                (FieldResolverExceptionWeight to 0.0) +
                (NodeResolverExceptionWeight to 0.0) +
                (VariablesResolverExceptionWeight to 0.0)

            val viaduct = Arb.viaduct(schema, cfg).next(RandomSource.seeded(0))
            val rendered = viaduct.dump()

            assertTrue(rendered.contains("Viaduct Descriptor"))
            assertTrue(rendered.contains("schemas:"))
            assertTrue(rendered.contains("type Foo"))
            assertTrue(rendered.contains("field resolvers:"))
            assertTrue(rendered.contains("Query.foo"))
            assertTrue(rendered.contains("isSelective: true"))
            assertTrue(rendered.contains("objectSelectionSet:"))
            assertTrue(rendered.contains("querySelectionSet:"))
        }
}
