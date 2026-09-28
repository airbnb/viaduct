@file:Suppress("ForbiddenImport")

package semantics.resolver26

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import model.Arguments
import model.ErrorEngineResult
import model.ObjectEngineResult
import model.emptyFragmentOf
import model.fragmentFrom
import model.operationSelectionsFrom
import model.outputValue
import model.requireObjectField
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromObjectField
import model.testing.fromQueryField
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import semantics.correctresolution.CorrectnessResolverObserver
import semantics.shared.SharedOperationContext
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Regression coverage: the independently included shared producer has transitive inputs. */
class SingularQueryGuardIsolationTest : Resolver26DispatcherResource {
    @TestFactory
    fun `failed alternative cannot poison transitive shared input`() =
        listOf("query", "objectProvider", "queryProvider").flatMap { mode ->
            listOf("bad good", "good bad").map { order ->
                DynamicTest.dynamicTest("$mode order=$order") {
                    runBlocking {
                        val calls = AtomicInteger()
                        val failure = IllegalStateException("bad owner's provider")
                        val world = world(mode, calls) { name -> if (name == "bad") throw failure }
                        val observer = CorrectnessResolverObserver()
                        val operation = SharedOperationContext.create(world.assumptions, resolverObserver = observer)
                        val request = Job()
                        try {
                            val result = operation.startResolve(
                                world.assumptions.operationSelectionsFrom("{ $order }"),
                                CoroutineScope(resolverDispatcher + request),
                            )
                            val bad = withTimeout(5_000) { result.getCell(key(world, "bad")).getValue().await() }
                            assertIs<ErrorEngineResult>(bad)
                            val good = withTimeout(5_000) { result.getCell(key(world, "good")).getValue().await() }
                            val shared = observer.allQueryOERs().values.single { it.isDemanded() }.occurrence.target
                            assertTrue(withTimeout(5_000) { shared.getCell(key(world, "source")).fetchActivated() }, "A ready true alternative must activate source")
                            assertEquals(7, withTimeout(5_000) { shared.getCell(key(world, "leaf")).getValue().await() })
                            assertEquals(1, calls.get(), "The transitive leaf must be produced once")
                            assertEquals(7, good, "An unrelated failed owner must not poison the shared producer's $mode input")
                        } finally {
                            withTimeout(5_000) { request.cancelAndJoin() }
                        }
                    }
                }
            }
        }

    @TestFactory
    fun `ready owner completes before unrelated suspended guard is released`() =
        listOf("query", "objectProvider", "queryProvider").map { mode ->
            DynamicTest.dynamicTest(mode) {
                runBlocking {
                    val slowStarted = CompletableDeferred<Unit>()
                    val fastStarted = CompletableDeferred<Unit>()
                    val releaseSlow = CompletableDeferred<Unit>()
                    val calls = AtomicInteger()
                    val world = world(mode, calls) { name ->
                        if (name == "bad") {
                            slowStarted.complete(Unit)
                            releaseSlow.await()
                        } else fastStarted.complete(Unit)
                    }
                    val observer = CorrectnessResolverObserver()
                    val operation = SharedOperationContext.create(world.assumptions, resolverObserver = observer)
                    val request = Job()
                    try {
                        val result = operation.startResolve(
                            world.assumptions.operationSelectionsFrom("{ bad good }"),
                            CoroutineScope(resolverDispatcher + request),
                        )
                        withTimeout(5_000) { slowStarted.await(); fastStarted.await() }
                        val good = result.getCell(key(world, "good"))
                        val beforeRelease = withTimeoutOrNull(1_000) { good.getValue().await() }
                        val shared = observer.allQueryOERs().values.single { it.isDemanded() }.occurrence.target
                        val activatedBeforeRelease = withTimeout(5_000) { shared.getCell(key(world, "source")).fetchActivated() }
                        val leafBeforeRelease = withTimeout(5_000) { shared.getCell(key(world, "leaf")).getValue().await() }
                        releaseSlow.complete(Unit)
                        assertEquals(7, withTimeout(5_000) { good.getValue().await() })
                        assertTrue(activatedBeforeRelease, "Source already activated while the slow guard was pending")
                        assertEquals(7, leafBeforeRelease, "Source's independent leaf is already available")
                        assertEquals(1, calls.get())
                        assertEquals(7, beforeRelease, "A ready owner must not wait for the other owner's guard in $mode")
                    } finally {
                        releaseSlow.complete(Unit)
                        withTimeout(5_000) { request.cancelAndJoin() }
                    }
                }
            }
        }

    private fun world(mode: String, leafCalls: AtomicInteger, beforeBinding: suspend (String) -> Unit): TestWorld =
        TestWorld.fromSDL(
            selectiveResolvers = true,
            schemaSDL = "type Query { bad: Int!, good: Int!, source: Int!, leaf: Int!, echo(value: Int!): Int! }",
            fieldResolvers = { schema ->
                val empty = schema.emptyFragmentOf("Query")
                val owners = listOf("bad", "good").associate { name ->
                    val field = schema.requireObjectField("Query", name)
                    field to fieldResolverOf(
                        objectFragment = empty,
                        queryFragment = schema.fragmentFrom(
                            "fragment Owner on Query { source @include(if: ${'$'}enabled) }",
                            variableField = field,
                        ),
                    ) { _, query, _ -> query.outputValue("source") }
                        .withVariablesProvider(setOf("enabled")) {
                            beforeBinding(name)
                            mapOf("enabled" to true)
                        }
                }
                val source = schema.requireObjectField("Query", "source")
                owners + mapOf(
                    source to fieldResolverOf(
                        objectFragment = if (mode == "objectProvider") {
                            schema.fragmentFrom("fragment Local on Query { leaf echo(value: ${'$'}value) }", variableField = source)
                        } else empty,
                        queryFragment = when (mode) {
                            "query" -> schema.fragmentFrom("fragment Input on Query { leaf }")
                            "queryProvider" -> schema.fragmentFrom("fragment Input on Query { leaf echo(value: ${'$'}value) }", variableField = source)
                            else -> empty
                        },
                    ) { input, query, _ ->
                        when (mode) {
                            "objectProvider" -> input.outputValue("echo")
                            "queryProvider" -> query.outputValue("echo")
                            else -> query.outputValue("leaf")
                        }
                    },
                    schema.requireObjectField("Query", "leaf") to fieldResolverOf(empty) { _, _ -> leafCalls.incrementAndGet(); 7 },
                    schema.requireObjectField("Query", "echo") to fieldResolverOf(empty) { _, arguments -> arguments.fieldValues.getValue("value") },
                )
            },
            variableProviders = { schema ->
                val source = schema.requireObjectField("Query", "source")
                when (mode) {
                    "objectProvider" -> mapOf(Arguments.Variable.of(source, "value") to schema.fromObjectField(
                        objectFragmentSource = "fragment Provider on Query { leaf }", responsePath = listOf("leaf"), variableField = source,
                    ))
                    "queryProvider" -> mapOf(Arguments.Variable.of(source, "value") to schema.fromQueryField(
                        queryFragmentSource = "fragment Provider on Query { leaf }", responsePath = listOf("leaf"), variableField = source,
                    ))
                    else -> emptyMap()
                }
            },
        )

    private fun key(world: TestWorld, name: String) =
        ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", name), emptyMap())
}
