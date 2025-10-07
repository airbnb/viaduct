@file:Suppress("ForbiddenImport")

package viaduct.engine.api

import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.mocks.MockVariablesResolver
import viaduct.engine.api.mocks.mkEngineObjectData
import viaduct.engine.api.select.SelectionsParser

@ExperimentalCoroutinesApi
class VariablesResolverTest {
    private val engineCtx = mockk<EngineExecutionContext>()
    private val schema = MockSchema.mk("extend type Query { a:Int, b:Int }")
    private val objectData = mkEngineObjectData(
        schema.schema.queryType,
        mapOf("a" to 1, "b" to 2)
    )

    private fun assertMatch(
        expected: VariablesResolver,
        actual: VariablesResolver,
        arguments: Map<String, Any?> = emptyMap()
    ) = runBlocking {
        assertEquals(expected.variableNames, actual.variableNames)
        assertEquals(
            expected.resolve(mkResolverCtx(objectData, arguments)),
            actual.resolve(mkResolverCtx(objectData, arguments))
        )
    }

    private fun assertMatch(
        expected: List<VariablesResolver>,
        actual: List<VariablesResolver>,
        arguments: Map<String, Any?> = emptyMap()
    ) = runBlocking {
        val expMap = expected.toMap()
        val actMap = actual.toMap()
        assertEquals(expMap.keys, actMap.keys)
        expMap.forEach { (name, exp) ->
            assertMatch(exp, actMap[name]!!, arguments)
        }
    }

    private val a = VariablesResolver.const(mapOf("a" to 1))
    private val b = VariablesResolver.const(mapOf("b" to 2))
    private val c = VariablesResolver.const(mapOf("c" to 3))

    private fun mkResolverCtx(
        objData: EngineObjectData = objectData,
        arguments: Map<String, Any?> = emptyMap(),
        eCtx: EngineExecutionContext = engineCtx
    ): VariablesResolver.ResolveCtx = VariablesResolver.ResolveCtx(objData, arguments, eCtx)

    @Test
    fun `const -- empty`() {
        assertSame(VariablesResolver.Empty, VariablesResolver.const(emptyMap()))
        assertNotSame(VariablesResolver.Empty, a)
    }

    @Test
    fun `validated -- empty`(): Unit =
        runBlocking {
            assertSame(VariablesResolver.Empty, VariablesResolver.Empty.validated())
        }

    @Test
    fun `validated -- resolve`(): Unit =
        runBlocking {
            val vr = MockVariablesResolver("a") { mapOf("b" to 1) }

            // sanity
            assertEquals(
                mapOf("b" to 1),
                vr.resolve(mkResolverCtx())
            )

            assertThrows<IllegalStateException> {
                vr.validated().resolve(mkResolverCtx())
            }
        }

    @Test
    fun `validated -- null value`(): Unit =
        runBlocking {
            val vr = VariablesResolver.const(mapOf("a" to null))
            assertEquals(
                mapOf("a" to null),
                vr.validated().resolve(mkResolverCtx())
            )
        }

    @Test
    fun `validated -- equality`() {
        assertEquals(a.validated(), a.validated())
    }

    @Test
    fun `fromSelectionSetVariable -- empty`() {
        assertEquals(
            emptyList<VariablesResolver>(),
            VariablesResolver.fromSelectionSetVariables(
                ParsedSelections.empty("Query"),
                ParsedSelections.empty("Query"),
                emptyList()
            )
        )
    }

    @Test
    fun `fromSelectionSetVariable -- simple argument`() =
        assertMatch(
            listOf(VariablesResolver.const(mapOf("a" to 1))),
            VariablesResolver.fromSelectionSetVariables(
                ParsedSelections.empty("Query"),
                ParsedSelections.empty("Query"),
                listOf(
                    FromArgumentVariable("a", "x.y")
                )
            ),
            mapOf("x" to mapOf("y" to 1))
        )

    @Test
    fun `fromSelectionSetVariable -- multiple arguments`() =
        assertMatch(
            listOf(
                VariablesResolver.const(mapOf("a" to 1)),
                VariablesResolver.const(mapOf("b" to 2)),
            ),
            VariablesResolver.fromSelectionSetVariables(
                ParsedSelections.empty("Query"),
                ParsedSelections.empty("Query"),
                listOf(
                    FromArgumentVariable("a", "x.y"),
                    FromArgumentVariable("b", "z")
                )
            ),
            mapOf(
                "x" to mapOf("y" to 1),
                "z" to 2
            )
        )

    @Test
    fun `fromSelectionSetVariables -- field`() {
        val resolverSelections = SelectionsParser.parse("Query", "a b")
        assertMatch(
            listOf(
                VariablesResolver.const(mapOf("a" to 1)),
                VariablesResolver.const(mapOf("b" to 2)),
            ),
            VariablesResolver.fromSelectionSetVariables(
                resolverSelections,
                ParsedSelections.empty("Query"),
                listOf(
                    FromObjectFieldVariable("a", "a"),
                    FromObjectFieldVariable("b", "b")
                )
            )
        )
    }

    @Test
    fun `fromSelectionSetVariables -- path of FromSelectionVariable is empty`() {
        assertThrows<IllegalArgumentException> {
            VariablesResolver.fromSelectionSetVariables(
                ParsedSelections.empty("Query"),
                ParsedSelections.empty("Query"),
                listOf(
                    FromObjectFieldVariable("a", ""),
                )
            )
        }
    }

    @Test
    fun `fromSelectionSetVariables -- path of FromSelectionVariable is not in selection set`() {
        assertThrows<IllegalArgumentException> {
            VariablesResolver.fromSelectionSetVariables(
                ParsedSelections.empty("Query"),
                ParsedSelections.empty("Query"),
                listOf(
                    FromObjectFieldVariable("a", "a"),
                )
            )
        }
    }

    @Test
    fun `fromSelectionSetVariables -- FromQueryFieldVariable`() {
        val querySelections = SelectionsParser.parse("Query", "a b")
        assertMatch(
            listOf(
                VariablesResolver.const(mapOf("a" to 1)),
                VariablesResolver.const(mapOf("b" to 2)),
            ),
            VariablesResolver.fromSelectionSetVariables(
                ParsedSelections.empty("Query"),
                querySelections,
                listOf(
                    FromQueryFieldVariable("a", "a"),
                    FromQueryFieldVariable("b", "b")
                )
            )
        )
    }

    @Test
    fun `fromSelectionSetVariables -- FromQueryFieldVariable with empty path`() {
        assertThrows<IllegalArgumentException> {
            VariablesResolver.fromSelectionSetVariables(
                ParsedSelections.empty("Query"),
                ParsedSelections.empty("Query"),
                listOf(
                    FromQueryFieldVariable("a", ""),
                )
            )
        }
    }

    @Test
    fun `fromSelectionSetVariables -- FromQueryFieldVariable without query selections`() {
        assertThrows<IllegalStateException> {
            VariablesResolver.fromSelectionSetVariables(
                ParsedSelections.empty("Query"),
                null,
                listOf(
                    FromQueryFieldVariable("a", "a"),
                )
            )
        }
    }

    @Test
    fun `fromSelectionSetVariables -- mixed FromObjectFieldVariable and FromQueryFieldVariable`() {
        val objectSelections = SelectionsParser.parse("Query", "a")
        val querySelections = SelectionsParser.parse("Query", "b")
        assertMatch(
            listOf(
                VariablesResolver.const(mapOf("objVar" to 1)),
                VariablesResolver.const(mapOf("queryVar" to 2)),
            ),
            VariablesResolver.fromSelectionSetVariables(
                objectSelections,
                querySelections,
                listOf(
                    FromObjectFieldVariable("objVar", "a"),
                    FromQueryFieldVariable("queryVar", "b")
                )
            )
        )
    }

    @Test
    fun `VariablesResolver -- variableNames`(): Unit =
        runBlocking {
            // empty
            assertEquals(emptySet<String>(), emptyList<VariablesResolver>().variableNames)

            // single item
            assertEquals(setOf("a"), listOf(a).variableNames)

            // multi-item
            assertEquals(setOf("a", "b", "c"), listOf(a, b, c).variableNames)
        }

    @Test
    fun `VariablesResolver -- resolve`(): Unit =
        runBlocking {
            // empty
            assertEquals(
                emptyMap<String, Any?>(),
                emptyList<VariablesResolver>().resolve(mkResolverCtx())
            )

            // single-item
            assertEquals(
                mapOf("a" to 1),
                listOf(a).resolve(mkResolverCtx())
            )

            // multi-item
            assertEquals(
                mapOf("a" to 1, "b" to 2, "c" to 3),
                listOf(a, b, c).resolve(mkResolverCtx())
            )
        }

    @Test
    fun `VariablesResolver -- checkDisjoint`() {
        // empty
        assertDoesNotThrow {
            emptyList<VariablesResolver>().checkDisjoint()
        }

        // single item
        assertDoesNotThrow {
            listOf(a).checkDisjoint()
        }

        // multiple items, non-disjoint
        assertDoesNotThrow {
            listOf(a, b, c).checkDisjoint()
        }

        // repeated items
        assertThrows<IllegalStateException> {
            listOf(a, a).checkDisjoint()
        }
    }
}

private fun List<VariablesResolver>.toMap(): Map<String, VariablesResolver> =
    flatMap { vr ->
        vr.variableNames.map { vname -> vname to vr }
    }.toMap()
