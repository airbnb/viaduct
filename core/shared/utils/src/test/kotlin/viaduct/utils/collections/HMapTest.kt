package viaduct.utils.collections

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HMapTest {
    @Test
    fun `values are retrieved by typed keys`() {
        val stringKey = HMap.Key.of<String>("string")
        val numberKey = HMap.Key.of<Number>("number")
        val holder = HMap.Builder()
            .put(stringKey, "value")
            .put(numberKey, 42)
            .build()

        val stringValue: String = holder[stringKey]

        assertEquals("value", stringValue)
        assertEquals(42, holder[numberKey])
    }

    @Test
    fun `nullable values are supported by nullable keys`() {
        val key = HMap.Key.of<String?>("nullable")

        assertNull(HMap.Builder().put(key, null).build()[key])
    }

    @Test
    fun `missing key throws`() {
        val key = HMap.Key.of<String>("missing")

        val error = assertThrows<NoSuchElementException> {
            HMap.Builder().build()[key]
        }

        assertEquals("No value for '$key'", error.message)
    }

    @Test
    fun `contains distinguishes present null from a missing key`() {
        val present = HMap.Key.of<String?>("present")
        val missing = HMap.Key.of<String?>("missing")
        val holder = HMap.Builder().put(present, null).build()

        assertTrue(present in holder)
        assertFalse(missing in holder)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `default contains checks key presence through get`() {
        val present = HMap.Key.of<String>("present")
        val missing = HMap.Key.of<String>("missing")
        val holder =
            object : HMap {
                override fun <T> get(key: HMap.Key<T>): T {
                    if (key === present) {
                        return "value" as T
                    }
                    throw NoSuchElementException()
                }
            }

        assertTrue(present in holder)
        assertFalse(missing in holder)
    }

    @Test
    fun `keys use reference identity`() {
        val storedKey = HMap.Key.of<String>("same")
        val equalLookingKey = HMap.Key.of<String>("same")
        val holder = HMap.Builder().put(storedKey, "value").build()

        assertEquals("value", holder[storedKey])
        assertThrows<NoSuchElementException> {
            holder[equalLookingKey]
        }
    }

    @Test
    fun `key exposes diagnostic name and type`() {
        val key = HMap.Key.of<List<String>>("items")

        assertEquals("items", key.name)
        assertFalse(key.isMarkedNullable)
        assertEquals(List::class, key.klass)
        val identity = System.identityHashCode(key).toString(16)
        assertEquals("Key@$identity<kotlin.collections.List>(items)", key.toString())
    }

    @Test
    fun `unchecked cast can fool parameterized type checking`() {
        val key = HMap.Key.of<List<String>>("items")
        val erasedKey = erase(key)
        val holder = HMap.Builder().put(erasedKey, listOf(1)).build()

        assertEquals(1, holder[key].size)
        assertThrows<ClassCastException> {
            holder[key].single().length
        }
    }

    @Test
    fun `non-nullable key rejects null`() {
        val key = HMap.Key.of<String>("string")
        val erasedKey = erase(key)

        val error = assertThrows<IllegalArgumentException> {
            HMap.Builder().put(erasedKey, null)
        }

        assertEquals("$key: Unexpected null", error.message)
    }

    @Test
    fun `key rejects value with unexpected runtime type`() {
        val key = HMap.Key.of<Int>("number")
        val erasedKey = erase(key)

        val error = assertThrows<IllegalArgumentException> {
            HMap.Builder().put(erasedKey, "not a number")
        }

        assertEquals(
            "$key: Unexpected type class kotlin.String",
            error.message
        )
    }

    @Test
    fun `build transfers the builder's current values`() {
        val k1 = HMap.Key.of<Any>("k1")
        val k2 = HMap.Key.of<Any>("k2")
        val builder = HMap.Builder().put(k1, "v1-before")
        val firstHolder = builder.build()

        builder.put(k1, "v1-after")
        builder.put(k2, "v2")
        val secondHolder = builder.build()

        assertEquals("v1-before", firstHolder[k1])
        assertTrue(k2 !in firstHolder)
        assertEquals("v1-after", secondHolder[k1])
        assertEquals("v2", secondHolder[k2])
    }

    @Test
    fun `holder retains stored object identity`() {
        val key = HMap.Key.of<Any>("value")
        val value = Any()

        assertSame(value, HMap.Builder().put(key, value).build()[key])
    }

    @Test
    fun `singleton stores value at default key`() {
        val value = Any()
        val holder = HMap.singleton(value)
        val nonDefault = HMap.Key.of<Any>("non-default")

        assertSame(value, holder[HMap.Key.DEFAULT])
        assertTrue(HMap.Key.DEFAULT in holder)
        assertFalse(nonDefault in holder)
    }

    @Test
    fun `singleton supports null`() {
        assertNull(HMap.singleton(null)[HMap.Key.DEFAULT])
    }

    @Test
    fun `singleton rejects non-default key`() {
        val key = HMap.Key.of<Any?>("DEFAULT")

        assertThrows<NoSuchElementException> {
            HMap.singleton("value")[key]
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun erase(key: HMap.Key<*>): HMap.Key<Any?> = key as HMap.Key<Any?>
}
