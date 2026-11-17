@file:Suppress("ForbiddenImport")

package viaduct.engine.api.testing

import graphql.Scalars
import graphql.schema.GraphQLObjectType
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.apache.commons.jexl3.JexlException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ResolvedEngineObjectData

class JEXLScriptTest {
    @Test
    fun `scripts cannot construct objects enable pragmas or assign global bindings`() {
        for (source in listOf("new('java.lang.String', 'value')", "#pragma jexl.options '+safe'\n1", "seed = 7")) {
            assertThrows<JexlException.Feature>(source) { prepareJEXLScript("Example/value", source, emptyMap()) }
        }
    }

    @Test
    fun `shared sandbox denies class access while permitting bound operations`(): Unit =
        runBlocking {
            val type = GraphQLObjectType.newObject().name("Item").field { it.name("n").type(Scalars.GraphQLInt) }.build()
            val input = ResolvedEngineObjectData(type, mapOf("n" to 3))
            val counter = AtomicInteger()
            val shared = mapOf("counter" to counter)
            val bindings = PreparedJEXLChecker("Example/value.checker", "allow", emptyList(), shared).bindings(emptyMap(), emptyMap())
            bindings.set("objectValue", jexlObjectValue(input, fromRSS = true))
            bindings.set("query", JEXLExecutionCallable(EmptyCoroutineContext, false) { _, _ -> input })
            bindings.set("mutation", JEXLExecutionCallable(EmptyCoroutineContext, true) { _, _ -> input })
            bindings.set("ref", JEXLReferenceCallable { _, _ -> 7 })
            val forbidden = listOf("''.class", "objectValue.getClass()", "counter.getClass()", "counter.class") +
                listOf("query", "mutation", "ref", "denyWithMessage").flatMap { listOf("$it.class", "$it.getClass()") }
            forbidden.forEach { source ->
                assertThrows<JexlException>(source) { prepareJEXLScript("Example/value", source, shared).evaluateJEXL(bindings) }
            }
            for ((source, expected) in listOf(
                "objectValue.n" to 3,
                "query('{ n }').n" to 3,
                "mutation('{ n }').n" to 3,
                "ref('item')" to 7,
                "counter.incrementAndGet()" to 1,
            )) {
                assertEquals(expected, prepareJEXLScript("Example/value", source, shared).evaluateJEXL(bindings), source)
            }
        }

    @Test
    fun `default denials do not share mutable exceptions between invocations`(): Unit =
        runBlocking {
            val checker = PreparedJEXLChecker("Example/value.checker", "deny", emptyList(), emptyMap())
            val first = assertInstanceOf(CheckerResult.Error::class.java, checker.evaluate(checker.bindings(emptyMap(), emptyMap())))
            first.error.addSuppressed(IllegalStateException("first invocation only"))
            val second = assertInstanceOf(CheckerResult.Error::class.java, checker.evaluate(checker.bindings(emptyMap(), emptyMap())))
            assertTrue(second.error.suppressed.isEmpty())
            assertNotSame(first.error, second.error)
            assertEquals("Example/value.checker denied access", second.error.message)
        }

    @Test
    fun `evaluates ordinary bindings and reports the script coordinate and line`(): Unit =
        runBlocking {
            val script = prepareJEXLScript("Example/value", "var n = seed + extra;\nreturn n;", mapOf("extra" to 3))
            val bindings = createJEXLScriptBindings(listOf("seed"), mapOf("seed" to 4), mapOf("extra" to 3))
            assertEquals(7, withContext(jexlDispatcher) { script.evaluateJEXL(bindings) })
            val failure = assertThrows<JexlException> {
                prepareJEXLScript("Example/value", "var n = 1;\nreturn missing;", emptyMap()).evaluateJEXL(bindings)
            }
            assertEquals("Example/value", failure.info.name)
            assertEquals(2, failure.info.line)
        }

    @Test
    fun `uncaught throws report script locations and capture the evaluation stack`(): Unit =
        runBlocking {
            val script = prepareJEXLScript("Example/value", "var n = 1;\n  throw 'deliberate failure';\nreturn missing;", emptyMap())
            val failure = assertThrows<RuntimeException> { script.evaluateJEXL(createJEXLScriptBindings(emptyList(), emptyMap(), emptyMap())) }
            assertEquals(RuntimeException::class.java, failure.javaClass)
            assertEquals("Example/value@2:3: deliberate failure", failure.message)
            assertEquals("deliberate failure", assertInstanceOf(JexlException.Throw::class.java, failure.cause).value)
            assertTrue(failure.stackTrace.any { it.className == "viaduct.engine.api.testing.JEXLScriptKt" && it.methodName == "evaluateJEXL" })
        }

    @Test
    fun `deferred output validation and conversion preserve original failures and cancellation`(): Unit =
        runBlocking {
            val type = GraphQLObjectType.newObject().name("Item").field { it.name("n").type(Scalars.GraphQLInt) }.build()
            for (failure in listOf(IllegalStateException("read failed"), CancellationException("read cancelled"))) {
                val input = object : EngineObjectData.Sync by ResolvedEngineObjectData(type, mapOf("n" to null)) {
                    override fun get(selection: String): Any? = throw failure
                }
                val bindings = createJEXLScriptBindings(emptyList(), emptyMap(), emptyMap())
                bindings.set("objectValue", jexlObjectValue(input))
                val script = prepareJEXLScript("Example/value", "objectValue", emptyMap())
                val validation = assertThrows<Exception> {
                    script.evaluateJEXL(bindings) { output -> requireJEXLResolverOutput(output, "Example/value") }
                }
                assertSame(failure, validation)
                val conversion = assertThrows<Exception> {
                    script.evaluateJEXL(bindings) { output -> (output as Map<*, *>)["n"] }
                }
                assertSame(failure, conversion)
            }
        }

    @Test
    fun `throws caught by scripts do not cross the evaluation boundary`(): Unit =
        runBlocking {
            val script = prepareJEXLScript("Example/value", "try { throw 'recoverable'; } catch (var failure) { return 7; }", emptyMap())
            assertEquals(7, script.evaluateJEXL(createJEXLScriptBindings(emptyList(), emptyMap(), emptyMap())))
        }

    @Test
    fun `providers and checkers propagate explicit throws`(): Unit =
        runBlocking {
            val provider = PreparedJEXLVariablesProvider("Example/value.vars", emptyList(), setOf("computed"), "throw 'provider failed';", emptyMap())
            val providerFailure = assertThrows<RuntimeException> { provider.evaluate(emptyMap()) }
            assertEquals("Example/value.vars@1:1: provider failed", providerFailure.message)
            assertInstanceOf(JexlException.Throw::class.java, providerFailure.cause)

            val checker = PreparedJEXLChecker("Example/value.checker", "throw 'checker failed';", emptyList(), emptyMap())
            val checkerFailure = assertThrows<RuntimeException> { checker.evaluate(checker.bindings(emptyMap(), emptyMap())) }
            assertEquals("Example/value.checker@1:1: checker failed", checkerFailure.message)
            assertInstanceOf(JexlException.Throw::class.java, checkerFailure.cause)
        }

    @Test
    fun `rejects the RSS marker in copied maps arrays and nested lists`() {
        for (marker in listOf(true, false, null)) {
            val marked = mapOf("value" to 7, JEXL_FROM_RSS to marker)
            for (output in listOf(marked, marked.toMap(), mapOf("child" to marked), listOf(listOf(marked)), arrayOf(marked))) {
                assertThrows<IllegalArgumentException> { requireJEXLResolverOutput(output, "Example/value") }
            }
        }
        requireJEXLResolverOutput(mapOf("value" to 7, "child" to listOf(mapOf("value" to 8))), "Example/value")
    }

    @Test
    fun `providers and checkers operate without registry definitions`(): Unit =
        runBlocking {
            val provider = PreparedJEXLVariablesProvider("Example/value.vars", listOf("seed"), setOf("computed"), "{'computed': seed * 2}", emptyMap())
            assertEquals(mapOf("computed" to 8), provider.evaluate(mapOf("seed" to 4)))
            val checker = PreparedJEXLChecker("Example/value.checker", "if (seed == 4) return allow; return denyWithMessage('unexpected seed');", listOf("seed"), emptyMap())
            assertSame(CheckerResult.Success, checker.evaluate(checker.bindings(mapOf("seed" to 4), emptyMap())))
            val denied = checker.evaluate(checker.bindings(mapOf("seed" to 5), emptyMap())) as CheckerResult.Error
            assertEquals("unexpected seed", denied.error.message)
        }

    @Test
    fun `ordinary argument maps remain read only and unmarked`(): Unit =
        runBlocking {
            val original = mapOf("value" to listOf(7))
            val bindings = createJEXLScriptBindings(listOf("input"), mapOf("input" to original), emptyMap())
            val input = bindings.get("input") as Map<*, *>
            assertFalse(JEXL_FROM_RSS in input)
            val script = prepareJEXLScript("Example/value", "var local = input; local.value[0] = 9", emptyMap())
            assertThrows<JexlException> { script.evaluateJEXL(bindings) }
            assertEquals(mapOf("value" to listOf(7)), original)
        }
}
