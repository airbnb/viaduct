package viaduct.tenant.runtime.featuretests

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import viaduct.api.context.NodeExecutionContext
import viaduct.tenant.runtime.featuretests.fixtures.Baz
import viaduct.tenant.runtime.featuretests.fixtures.FeatureTestBuilder
import viaduct.tenant.runtime.featuretests.fixtures.FeatureTestSchemaFixture
import viaduct.tenant.runtime.featuretests.fixtures.Query
import viaduct.tenant.runtime.featuretests.fixtures.UntypedFieldContext
import viaduct.tenant.runtime.featuretests.fixtures.assertJson

@ExperimentalCoroutinesApi
class NodeResolverTest {
    private fun builder(resolveBaz: suspend (ctx: NodeExecutionContext<Baz>) -> Baz): FeatureTestBuilder =
        FeatureTestBuilder()
            .grtPackage(Query.Reflection)
            .sdl(FeatureTestSchemaFixture.sdl)
            .resolver("Query" to "baz") { ctx ->
                ctx.nodeFor(ctx.globalIDFor(Baz.Reflection, "1"))
            }
            .resolver("Query" to "bazList") { ctx ->
                (1..5).map {
                    ctx.nodeFor(ctx.globalIDFor(Baz.Reflection, it.toString()))
                }
            }
            .nodeResolver(Baz.Reflection.name, resolveBaz)

    @Test
    fun `node resolver returns value`() {
        builder { ctx -> Baz.Builder(ctx).x(42).build() }
            .build()
            .assertJson(
                "{data: {baz: {x: 42}}}",
                "{baz {x}}",
            )
    }

    @Test
    fun `node resolver is invoked for id-only resolution`() {
        var invoked = false
        builder { ctx -> Baz.Builder(ctx).build().also { invoked = true } }
            .build()
            .execute("{ baz { id } }")
            .let { _ ->
                assertTrue(invoked)
            }
    }

    @Test
    fun `node resolver throws`() {
        builder { _ -> throw RuntimeException("msg") }
            .build()
            .execute("{ baz { x } }")
            .let { result ->
                assertEquals(mapOf("baz" to null), result.toSpecification()["data"])
                assertTrue(result.errors.any { it.path == listOf("baz") }) {
                    result.toSpecification().toString()
                }
            }
    }

    @Test
    fun `node field executes in parallel with node resolver`() {
        var yInvoked = false
        val result = builder { _ ->
            delay(50)
            throw RuntimeException("msg")
        }
            .resolver(
                "Baz" to "y",
                { _: UntypedFieldContext ->
                    yInvoked = true
                    "a"
                }
            )
            .build()
            .execute("{ baz { y } }")

        assertTrue(yInvoked)
        assertEquals(mapOf("baz" to null), result.toSpecification()["data"])
        assertTrue(result.errors.size == 1)
        assertEquals(listOf(listOf("baz")), result.errors.map { it.path })
    }

    @Test
    fun `awaits completion for node in required selection set`() {
        val result = builder { ctx ->
            if (ctx.id.internalID == "2") {
                delay(50)
                throw RuntimeException("msg")
            } else {
                Baz.Builder(ctx).x(1).build()
            }
        }
            .resolver(
                "Baz" to "anotherBaz",
                { ctx: UntypedFieldContext -> ctx.nodeFor(ctx.globalIDFor(Baz.Reflection, "2")) }
            )
            .resolver(
                "Baz" to "z",
                { ctx: UntypedFieldContext ->
                    // The point of this test is that this should wait the node resolver for
                    // `anotherBaz` to execute rather than immediately returning what's available,
                    // as we do when the required selection set is on the node itself. Since
                    // `anotherBaz` will resolve with an exception, this `get` call should throw.
                    ctx.objectValue.get<Baz>("anotherBaz")
                    5
                },
                "anotherBaz { id }"
            )
            .build()
            .execute("{ baz { z } }")

        assertEquals(mapOf("baz" to mapOf("z" to null)), result.toSpecification()["data"])
        assertEquals(listOf(listOf("baz", "z")), result.errors.map { it.path })
    }

    @Test
    fun `list of nodes`() {
        val result = builder { ctx ->
            val internalId = ctx.id.internalID.toInt()
            if (internalId % 2 == 0) {
                throw RuntimeException("msg")
            } else {
                Baz.Builder(ctx).x(internalId).build()
            }
        }
            .build()
            .execute("{ bazList { x } }")

        val expectedResultData = mapOf(
            "bazList" to listOf(
                mapOf("x" to 1),
                null,
                mapOf("x" to 3),
                null,
                mapOf("x" to 5),
            ),
        )

        assertEquals(expectedResultData, result.toSpecification()["data"])
        assertEquals(listOf(listOf("bazList", 1), listOf("bazList", 3)), result.errors.map { it.path })
    }

    @Test
    fun `node resolver does not batch`() {
        val execCounts = ConcurrentHashMap<String, AtomicInteger>()
        val result = builder { ctx ->
            val internalId = ctx.id.internalID
            execCounts.computeIfAbsent(internalId) { AtomicInteger(0) }.incrementAndGet()
            Baz.Builder(ctx).x(internalId.toInt()).build()
        }
            .build()
            .execute("{bazList { x }}")

        // Verify each node was resolved individually (not batched)
        assertEquals(mapOf("1" to 1, "2" to 1, "3" to 1, "4" to 1, "5" to 1), execCounts.mapValues { it.value.get() })

        // Verify the results are correct
        val expectedData = mapOf(
            "bazList" to listOf(
                mapOf("x" to 1),
                mapOf("x" to 2),
                mapOf("x" to 3),
                mapOf("x" to 4),
                mapOf("x" to 5)
            )
        )
        assertEquals(expectedData, result.toSpecification()["data"])
    }

    @Test
    @Disabled("flaky")
    fun `node resolver reads from dataloader cache`() {
        val execCounts = ConcurrentHashMap<String, AtomicInteger>()
        builder { ctx ->
            val internalId = ctx.id.internalID
            execCounts.computeIfAbsent(internalId) { AtomicInteger(0) }.incrementAndGet()
            Baz.Builder(ctx).x(2).build()
        }
            .resolver("Query" to "baz") { ctx ->
                ctx.nodeFor(ctx.globalIDFor(Baz.Reflection, "1"))
            }
            .resolver("Baz" to "anotherBaz") { ctx ->
                ctx.nodeFor(ctx.globalIDFor(Baz.Reflection, "1"))
            }
            .build()
            .execute("{ baz { x anotherBaz { id x }}}")
            .assertJson("""{"data": {"baz": {"x":2, "anotherBaz":{"id":"QmF6OjE=", "x":2}}}}""")

        assertEquals(mapOf("1" to 1), execCounts.mapValues { it.value.get() })
    }

    @Test
    fun `node resolver does not read from dataloader cache if selection set does not cover`() {
        val execCounts = ConcurrentHashMap<String, AtomicInteger>()
        builder { ctx ->
            val internalId = ctx.id.internalID
            execCounts.computeIfAbsent(internalId) { AtomicInteger(0) }.incrementAndGet()
            Baz.Builder(ctx).x(2).x2("foo").build()
        }
            .resolver("Query" to "baz") { ctx ->
                ctx.nodeFor(ctx.globalIDFor(Baz.Reflection, "1"))
            }
            .resolver("Baz" to "anotherBaz") { ctx ->
                ctx.nodeFor(ctx.globalIDFor(Baz.Reflection, "1"))
            }
            .build()
            .execute("{ baz { x anotherBaz { x x2 }}}")
            .assertJson("""{"data": {"baz": {"x":2, "anotherBaz":{"x":2, "x2":"foo"}}}}""")

        assertEquals(mapOf("1" to 2), execCounts.mapValues { it.value.get() })
    }
}
