package viaduct.utils.collections

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals

// Groups items into equality classes. Items in the same group must be equal to each other
// (reflexivity, symmetry, transitivity) and unequal to every item in every other group.
// hashCode must be consistent within each group. Call testEquals() to run all assertions.
internal class EqualsTesterHelper {
    private val groups = mutableListOf<List<Any>>()

    fun addEqualityGroup(vararg items: Any): EqualsTesterHelper {
        groups += items.toList()
        return this
    }

    fun testEquals() {
        groups.forEachIndexed { gi, group ->
            group.forEach { item ->
                // Reflexivity
                assertEquals(item, item) { "group[$gi]: $item should equal itself" }

                // Equal to all others in same group (symmetry + hash consistency)
                group.forEach { other ->
                    assertEquals(item, other) { "group[$gi]: $item should equal $other" }
                    assertEquals(other, item) { "group[$gi]: $other should equal $item (symmetry)" }
                    assertEquals(item.hashCode(), other.hashCode()) {
                        "group[$gi]: hashCode($item)=${item.hashCode()} != hashCode($other)=${other.hashCode()}"
                    }
                }

                // Unequal to items in other groups (gj > gi avoids checking each pair twice)
                groups.forEachIndexed { gj, otherGroup ->
                    if (gj > gi) {
                        otherGroup.forEach { other ->
                            assertNotEquals(item, other) {
                                "group[$gi] item $item should not equal group[$gj] item $other"
                            }
                            assertNotEquals(other, item) {
                                "group[$gj] item $other should not equal group[$gi] item $item (symmetry)"
                            }
                        }
                    }
                }

                // Not equal to null
                assertNotEquals(item, null) { "group[$gi]: $item should not equal null" }

                // Not equal to an unrelated object
                assertNotEquals(item, UNRELATED_OBJECT) {
                    "group[$gi]: $item should not equal an unrelated object"
                }

                // toString() must not throw
                item.toString()
            }
        }
    }

    private companion object {
        val UNRELATED_OBJECT = object {
            override fun equals(other: Any?) = false

            override fun hashCode() = -1
        }
    }
}
