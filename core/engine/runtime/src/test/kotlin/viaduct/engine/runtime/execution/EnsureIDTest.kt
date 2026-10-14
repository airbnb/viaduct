package viaduct.engine.runtime.execution

import graphql.execution.ResultPath
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EnsureIDTest {
    @Test
    fun `a delivery group keeps its id after other groups are registered`() {
        val ensureId = EnsureID()
        val first = DeferDeliveryGroup(ResultPath.rootPath(), "first", null)
        val second = DeferDeliveryGroup(ResultPath.rootPath(), "second", null)

        assertEquals("0", ensureId(first))
        assertEquals("1", ensureId(second))
        assertEquals("0", ensureId(first))
        assertEquals("1", ensureId(second))
    }

    @Test
    fun `delivery groups with identical contents receive distinct ids`() {
        val ensureId = EnsureID()
        val parent = DeferDeliveryGroup(ResultPath.rootPath(), "parent", null)
        val path = ResultPath.rootPath().segment("items").segment(1)
        val first = DeferDeliveryGroup(path, "details", parent)
        val second = DeferDeliveryGroup(path, "details", parent)

        assertEquals("0", ensureId(first))
        assertEquals("1", ensureId(second))
    }

    @Test
    fun `allocator instances have independent id sequences`() {
        val firstAllocator = EnsureID()
        val secondAllocator = EnsureID()
        val first = DeferDeliveryGroup(ResultPath.rootPath(), null, null)
        val second = DeferDeliveryGroup(ResultPath.rootPath(), null, null)

        assertEquals("0", firstAllocator(first))
        assertEquals("1", firstAllocator(second))
        assertEquals("0", secondAllocator(second))
        assertEquals("1", secondAllocator(first))
        assertEquals("0", firstAllocator(first))
    }

    @Test
    fun `concurrent callers reuse ids for the same groups`() {
        val ensureId = EnsureID()
        val groups = List(100) { DeferDeliveryGroup(ResultPath.rootPath(), "details", null) }

        val results = runConcurrently(8) { groups.map(ensureId::invoke) }

        val expectedIds = groups.indices.map(Int::toString)
        results.forEach { it.shouldContainExactly(expectedIds) }
        assertEquals("100", ensureId(DeferDeliveryGroup(ResultPath.rootPath(), "next", null)))
    }

    @Test
    fun `concurrent callers assign unique ids to distinct groups`() {
        val ensureId = EnsureID()
        val groupsByCaller = List(8) {
            List(100) { DeferDeliveryGroup(ResultPath.rootPath(), "details", null) }
        }

        val results = runConcurrently(groupsByCaller.size) { caller ->
            groupsByCaller[caller].map(ensureId::invoke)
        }

        val ids = results.flatten()
        ids.shouldContainExactlyInAnyOrder(ids.indices.map(Int::toString))
        groupsByCaller.flatten().zip(ids).forEach { (group, id) ->
            assertEquals(id, ensureId(group))
        }
    }

    private fun <T> runConcurrently(
        callers: Int,
        action: (Int) -> T
    ): List<T> {
        val executor = Executors.newFixedThreadPool(callers)
        val barrier = CyclicBarrier(callers)
        return try {
            val futures = (0 until callers).map { caller ->
                executor.submit<T> {
                    barrier.await(10, TimeUnit.SECONDS)
                    action(caller)
                }
            }
            futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }
}
