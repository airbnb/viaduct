package viaduct.engine.runtime.execution

import graphql.execution.ResultPath
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldBeEmpty
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class GetNewDeferMapTest {
    @Test
    fun `empty`() {
        GetNewDeferMap(emptyList(), ResultPath.rootPath(), emptyMap()).shouldBeEmpty()
    }

    @Test
    fun `no new usages preserves existing groups`() {
        val usage = DeferUsage(mkDefer("existing"), null)
        val group = DeferDeliveryGroup(ResultPath.rootPath(), "existing", null)
        val deferMap = mapOf(usage to group)

        assertSame(deferMap, GetNewDeferMap(emptyList(), ResultPath.rootPath().segment("child"), deferMap))
    }

    @Test
    fun `root groups preserve absent empty and non-empty labels`() {
        val path = ResultPath.rootPath()
        val usages = listOf(null, "", "details").map { DeferUsage(mkDefer(it), null) }

        val result = GetNewDeferMap(usages, path, emptyMap())

        result.keys.shouldContainExactlyInAnyOrder(usages)
        result.values.map { it.label }.shouldContainExactly(null, "", "details")
        result.values.forEach {
            assertEquals(path, it.path)
            assertNull(it.parent)
        }
    }

    @Test
    fun `a new usage links to an existing parent without changing the input map`() {
        val parent = DeferUsage(mkDefer("parent"), null)
        val parentGroup = DeferDeliveryGroup(ResultPath.rootPath(), "parent", null)
        val child = DeferUsage(mkDefer("child"), parent)
        val path = ResultPath.rootPath().segment("aliasedItems").segment(2)
        val deferMap = mutableMapOf(parent to parentGroup)

        val result = GetNewDeferMap(listOf(child), path, deferMap)

        result.keys.shouldContainExactlyInAnyOrder(parent, child)
        assertSame(parentGroup, result.getValue(parent))
        assertSame(parentGroup, result.getValue(child).parent)
        assertEquals(path, result.getValue(child).path)
        assertEquals("child", result.getValue(child).label)
        assertEquals(mapOf(parent to parentGroup), deferMap)
    }

    @Test
    fun `nested new usages link to parents created in the same call`() {
        val parent = DeferUsage(mkDefer("parent"), null)
        val child = DeferUsage(mkDefer("child"), parent)
        val grandchild = DeferUsage(mkDefer("grandchild"), child)
        val path = ResultPath.rootPath().segment("hero")

        val result = GetNewDeferMap(listOf(parent, child, grandchild), path, emptyMap())

        result.keys.shouldContainExactlyInAnyOrder(parent, child, grandchild)
        assertNull(result.getValue(parent).parent)
        assertSame(result.getValue(parent), result.getValue(child).parent)
        assertSame(result.getValue(child), result.getValue(grandchild).parent)
        result.values.forEach { assertEquals(path, it.path) }
    }

    @Test
    fun `separate unlabeled usages create distinct groups at the same path`() {
        val first = DeferUsage(mkDefer(null), null)
        val second = DeferUsage(mkDefer(null), null)
        val path = ResultPath.rootPath()

        val result = GetNewDeferMap(listOf(first, second), path, emptyMap())

        result.keys.shouldContainExactlyInAnyOrder(first, second)
        assertNotSame(result.getValue(first), result.getValue(second))
        assertNotEquals(result.getValue(first), result.getValue(second))
        result.values.forEach {
            assertEquals(path, it.path)
            assertNull(it.label)
            assertNull(it.parent)
        }
    }

    @Test
    fun `the same usage creates independent groups for different list items`() {
        val usage = DeferUsage(mkDefer("details"), null)
        val path = ResultPath.rootPath().segment("aliasedItems")

        val first = GetNewDeferMap(listOf(usage), path.segment(0), emptyMap()).getValue(usage)
        val second = GetNewDeferMap(listOf(usage), path.segment(1), emptyMap()).getValue(usage)

        assertNotSame(first, second)
        assertEquals(path.segment(0), first.path)
        assertEquals(path.segment(1), second.path)
        assertEquals("details", first.label)
        assertEquals("details", second.label)
    }

    @Test
    fun `revisiting a usage creates a fresh group without changing its previous mapping`() {
        val usage = DeferUsage(mkDefer("details"), null)
        val path = ResultPath.rootPath()
        val previousGroup = DeferDeliveryGroup(path, "details", null)
        val deferMap = mutableMapOf(usage to previousGroup)

        val result = GetNewDeferMap(listOf(usage), path, deferMap)

        result.keys.shouldContainExactlyInAnyOrder(usage)
        assertNotSame(previousGroup, result.getValue(usage))
        assertNotEquals(previousGroup, result.getValue(usage))
        assertEquals(path, result.getValue(usage).path)
        assertEquals("details", result.getValue(usage).label)
        assertNull(result.getValue(usage).parent)
        assertSame(previousGroup, deferMap.getValue(usage))
    }
}
