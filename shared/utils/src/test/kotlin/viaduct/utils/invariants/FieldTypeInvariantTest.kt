package viaduct.utils.invariants

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Invariant checker that matches field types, but not field names.
 */
class FieldTypeInvariantTest {
    class Item(
        val value: Int,
        val name: String
    )

    class Container(
        val items: List<Item>,
        val numbers: List<Int>
    )

    @Test
    fun `check should pass for class with properties of different types`() {
        val item = Item(1, "one")
        val fieldTypeInvariant = FieldTypeInvariant(
            Item::class,
            listOf(
                FieldTypeInvariant(String::class),
                FieldTypeInvariant(Int::class)
            )
        )
        assertDoesNotThrow { fieldTypeInvariant.check(item) }
    }

    @Test
    fun `check should throw if class has incorrect property types`() {
        class WrongItem(
            val value: String
        )
        val container = Container(listOf(Item(1, "one"), Item(2, "two")), listOf(1, 2, 3))
        val fieldTypeInvariant = FieldTypeInvariant(
            Container::class,
            listOf(
                FieldTypeInvariant(WrongItem::class),
                FieldTypeInvariant(Int::class)
            )
        )
        assertThrows(InvariantException::class.java) { fieldTypeInvariant.check(container) }
    }

    @Test
    fun `check should pass for list with values of different types`() {
        val mixedList = listOf(1, "two", 3.0)
        val fieldTypeInvariant = FieldTypeInvariant(
            List::class,
            listOf(
                FieldTypeInvariant(Int::class),
                FieldTypeInvariant(String::class),
                FieldTypeInvariant(Double::class)
            )
        )
        assertDoesNotThrow { fieldTypeInvariant.check(mixedList) }
    }

    @Test
    fun `check should throw if list contains incorrect value types`() {
        val mixedList = listOf(1, "two", 3.0)
        val fieldTypeInvariant = FieldTypeInvariant(
            List::class,
            listOf(
                FieldTypeInvariant(Int::class),
                FieldTypeInvariant(String::class),
                FieldTypeInvariant(Boolean::class)
            )
        )
        assertThrows(InvariantException::class.java) { fieldTypeInvariant.check(mixedList) }
    }

    @Test
    fun `check should pass for HashMap with generic types`() {
        val map = hashMapOf("key1" to "value1", "key2" to "value2")
        val fieldTypeInvariant = FieldTypeInvariant(
            typeInfo<HashMap<String, String>>()
        )
        assertDoesNotThrow { fieldTypeInvariant.check(map) }
    }

    @Test
    fun `check should throw for HashMap with incorrect generic types`() {
        val map = hashMapOf("key1" to 1, "key2" to 2)
        val fieldTypeInvariant = FieldTypeInvariant(
            typeInfo<HashMap<String, String>>()
        )
        assertThrows(InvariantException::class.java) { fieldTypeInvariant.check(map) }
    }

    @Test
    fun `check should pass for HashMap with mixed generic types`() {
        val map = HashMap<String, Any>(hashMapOf("key1" to "value1", "key2" to 2))
        val fieldTypeInvariant = FieldTypeInvariant(
            typeInfo<HashMap<String, Any>>()
        )
        assertDoesNotThrow { fieldTypeInvariant.check(map) }
    }

    @Test
    fun `check should throw for HashMap with incorrect mixed generic types`() {
        val map = hashMapOf("key1" to "value1", "key2" to 2)
        val fieldTypeInvariant = FieldTypeInvariant(
            typeInfo<HashMap<String, String>>()
        )
        assertThrows(InvariantException::class.java) { fieldTypeInvariant.check(map) }
    }
}
