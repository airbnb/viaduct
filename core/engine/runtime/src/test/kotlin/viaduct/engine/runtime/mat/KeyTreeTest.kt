package viaduct.engine.runtime.mat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.arbitrary.graphql.asViaductSchema
import viaduct.engine.runtime.ObjectEngineResult
import viaduct.engine.runtime.mat.KeyTreeFilter as FilterPredicate

class KeyTreeTest {
    private val schema =
        """
        | type Foo {
        |   a: Int
        |   b: Bar
        |   c(a: Int): Bar
        |   x(id: Int): Bar
        | }
        | type Bar {
        |   a: Int
        |   b: Int
        |   c: Int
        | }
        """.trimMargin().asViaductSchema
    private val fooType = checkNotNull(schema.schema.getObjectType("Foo"))

    @Nested
    inner class Identity {
        @Test
        fun `self`() {
            val a = KeyTree.build(schema)
            val b = KeyTree.build(schema)
            assertEquals(a, a)
            assertEquals(a, b)
        }

        @Test
        fun `unordered fields`() {
            val a = KeyTree.build(schema) {
                field("Foo", key("a"))
                field("Foo", key("b"))
            }
            val b = KeyTree.build(schema) {
                field("Foo", key("b"))
                field("Foo", key("a"))
            }
            assertEquals(a, b)
        }
    }

    @Nested
    inner class Construction {
        @Test
        fun `snapshots caller-owned outer map`() {
            val selectedKey = ObjectEngineResult.Key("a")
            val byKey = mutableMapOf(selectedKey to KeyTree.empty)
            val byType = mutableMapOf(fooType to byKey)
            val tree = KeyTree(byType)
            val expected = KeyTree(mapOf(fooType to mapOf(selectedKey to KeyTree.empty)))
            val originalHashCode = tree.hashCode()

            byType.clear()

            assertEquals(expected, tree)
            assertEquals(originalHashCode, tree.hashCode())
            assertEquals(setOf("a"), tree.responseKeysForType(fooType))
        }

        @Test
        fun `snapshots caller-owned inner maps`() {
            val selectedKey = ObjectEngineResult.Key("a")
            val byKey = mutableMapOf(selectedKey to KeyTree.empty)
            val tree = KeyTree(mapOf(fooType to byKey))
            val expected = KeyTree(mapOf(fooType to mapOf(selectedKey to KeyTree.empty)))
            val originalHashCode = tree.hashCode()

            byKey[ObjectEngineResult.Key("b")] = KeyTree.empty

            assertEquals(expected, tree)
            assertEquals(originalHashCode, tree.hashCode())
            assertEquals(setOf("a"), tree.responseKeysForType(fooType))
        }

        @Test
        fun `keys by type does not expose a mutable outer map`() {
            val tree = KeyTree(
                mutableMapOf(
                    fooType to mutableMapOf(ObjectEngineResult.Key("a") to KeyTree.empty)
                )
            )

            @Suppress("UNCHECKED_CAST")
            val byType = tree.keysByType() as MutableMap<Any?, Any?>

            assertThrows<UnsupportedOperationException> {
                byType.clear()
            }
        }

        @Test
        fun `keys by type does not expose mutable inner maps`() {
            val tree = KeyTree(
                mutableMapOf(
                    fooType to mutableMapOf(ObjectEngineResult.Key("a") to KeyTree.empty)
                )
            )

            @Suppress("UNCHECKED_CAST")
            val byKey = tree.keysByType().getValue(fooType) as MutableMap<Any?, Any?>

            assertThrows<UnsupportedOperationException> {
                byKey.clear()
            }
        }

        @Test
        fun `typed empty maps are canonical empty trees`() {
            val typedEmpty = KeyTree(mapOf(fooType to emptyMap()))

            assertEquals(KeyTree.empty, typedEmpty)
            assertEquals(KeyTree.empty.hashCode(), typedEmpty.hashCode())
            assertTrue(typedEmpty.keysByType().isEmpty())
        }
    }

    @Nested
    inner class IsEmpty {
        @Test
        fun empty() {
            assertTrue(KeyTree.empty.isEmpty())
        }

        @Test
        fun objects() {
            assertTrue(KeyTree.build(schema).isEmpty())
            assertFalse(
                KeyTree.build(schema) {
                    field("Foo", key("x"))
                }.isEmpty()
            )
        }

        @Test
        fun `typed empty object entries are empty`() {
            assertTrue(KeyTree(mapOf(fooType to emptyMap())).isEmpty())
        }
    }

    @Nested
    inner class Union {
        @Test
        fun empty() {
            val foo = KeyTree.build(schema) {
                field("Foo", key("x"))
            }
            assertEquals(KeyTree.empty, KeyTree.empty + KeyTree.empty)
            assertEquals(foo, foo + KeyTree.empty)
            assertEquals(foo, KeyTree.empty + foo)
        }

        @Test
        fun `typed empty is canonical empty when unioned`() {
            val typedEmpty = KeyTree(mapOf(fooType to emptyMap()))

            assertEquals(KeyTree.empty, typedEmpty + KeyTree.empty)
            assertEquals(KeyTree.empty, KeyTree.empty + typedEmpty)
            assertEquals(typedEmpty + KeyTree.empty, KeyTree.empty + typedEmpty)
        }

        @Test
        fun self() {
            val foo = KeyTree.build(schema) {
                field("Foo", key("x"))
            }
            assertEquals(foo, foo + foo)
        }

        @Test
        fun disjoint() {
            val a = KeyTree.build(schema) {
                field("Foo", key("a"))
            }
            val b = KeyTree.build(schema) {
                field("Foo", key("b"))
            }
            assertEquals(
                KeyTree.build(schema) {
                    field("Foo", key("a"))
                    field("Foo", key("b"))
                },
                a + b
            )
        }

        @Test
        fun nested() {
            val a = KeyTree.build(schema) {
                field("Foo", key("x")) {
                    field("Bar", key("a"))
                }
            }
            val b = KeyTree.build(schema) {
                field("Foo", key("x")) {
                    field("Bar", key("b"))
                }
            }
            assertEquals(
                KeyTree.build(schema) {
                    field("Foo", key("x")) {
                        field("Bar", key("a"))
                        field("Bar", key("b"))
                    }
                },
                a + b
            )
        }
    }

    @Nested
    inner class Subtract {
        @Test
        fun `empty`() {
            val foo = KeyTree.build(schema) {
                field("Foo", key("x"))
            }
            assertEquals(KeyTree.empty, KeyTree.empty - KeyTree.empty)
            assertEquals(KeyTree.empty, KeyTree.empty - foo)
            assertEquals(foo, foo - KeyTree.empty)
        }

        @Test
        fun disjoint() {
            val a = KeyTree.build(schema) {
                field("Foo", key("x"))
            }
            val b = KeyTree.build(schema) {
                field("Foo", key("c"))
            }
            assertEquals(a, a - b)
        }

        @Test
        fun overlapping() {
            val a = KeyTree.build(schema) {
                field("Foo", key("a"))
                field("Foo", key("b"))
            }
            val b = KeyTree.build(schema) {
                field("Foo", key("a"))
                field("Foo", key("c"))
            }
            assertEquals(
                KeyTree.build(schema) {
                    field("Foo", key("b"))
                },
                a - b,
            )
        }

        @Test
        fun aliased() {
            val a = KeyTree.build(schema) {
                field("Foo", key("x", alias = "a"))
            }
            val b = KeyTree.build(schema) {
                field("Foo", key("x", alias = "b"))
            }
            assertEquals(a, a - b)
        }

        @Test
        fun argumented() {
            val a = KeyTree.build(schema) {
                field("Foo", key("c", arguments = mapOf("a" to 1)))
            }
            val b = KeyTree.build(schema) {
                field("Foo", key("c", arguments = mapOf("a" to 2)))
            }
            assertEquals(a, a - b)
        }

        @Test
        fun `nested overlapping`() {
            val a = KeyTree.build(schema) {
                field("Foo", key("x")) {
                    field("Bar", key("a"))
                    field("Bar", key("b"))
                }
            }
            val b = KeyTree.build(schema) {
                field("Foo", key("x")) {
                    field("Bar", key("a"))
                    field("Bar", key("c"))
                }
            }

            assertEquals(
                KeyTree.build(schema) {
                    field("Foo", key("x")) {
                        field("Bar", key("b"))
                    }
                },
                a - b,
            )
        }
    }

    @Nested
    inner class SubtreeForKey {
        @Test
        fun `selects exact alias and arguments`() {
            val selectedKey = ObjectEngineResult.Key(
                "x",
                alias = "b",
                arguments = mapOf("id" to 2),
            )
            val tree = KeyTree.build(schema) {
                field("Foo", key("x", alias = "a", arguments = mapOf("id" to 1))) {
                    field("Bar", key("a"))
                }
                field("Foo", selectedKey) {
                    field("Bar", key("c"))
                }
            }

            assertEquals(
                KeyTree.build(schema) {
                    field("Bar", key("c"))
                },
                tree.subtreeForKey(fooType, selectedKey),
            )
        }

        @Test
        fun `returns empty for a different field instance`() {
            val tree = KeyTree.build(schema) {
                field("Foo", key("x", alias = "a")) {
                    field("Bar", key("c"))
                }
            }

            assertEquals(
                KeyTree.empty,
                tree.subtreeForKey(fooType, ObjectEngineResult.Key("x", alias = "b")),
            )
        }
    }

    @Nested
    inner class ResponseKeysForType {
        @Test
        fun empty() {
            assertEquals(
                emptySet<String>(),
                KeyTree.empty.responseKeysForType(fooType)
            )
        }

        @Test
        fun `simple`() {
            assertEquals(
                setOf("a", "b"),
                KeyTree.build(schema) {
                    field("Foo", key("a"))
                    field("Foo", key("b"))
                }.responseKeysForType(fooType)
            )
        }

        @Test
        fun `aliased`() {
            assertEquals(
                setOf("b", "c"),
                KeyTree.build(schema) {
                    field("Foo", key("a", alias = "b"))
                    field("Foo", key("b", alias = "c"))
                }.responseKeysForType(fooType)
            )
        }

        @Test
        fun `argumented`() {
            assertEquals(
                setOf("c"),
                KeyTree.build(schema) {
                    field("Foo", key("c", arguments = mapOf("a" to 1)))
                }.responseKeysForType(fooType)
            )
        }
    }

    @Nested
    inner class ContainsKey {
        @Test
        fun `requires exact alias and arguments`() {
            val selectedKey = ObjectEngineResult.Key(
                "c",
                alias = "selected",
                arguments = mapOf("a" to 1),
            )
            val tree = KeyTree.build(schema) {
                field("Foo", selectedKey)
            }

            assertTrue(tree.containsKey(fooType, selectedKey))
            assertFalse(
                tree.containsKey(
                    fooType,
                    ObjectEngineResult.Key("c", alias = "other", arguments = mapOf("a" to 1)),
                )
            )
            assertFalse(
                tree.containsKey(
                    fooType,
                    ObjectEngineResult.Key("c", alias = "selected", arguments = mapOf("a" to 2)),
                )
            )
        }
    }

    @Nested
    inner class Filter {
        private val dropA: FilterPredicate = FilterPredicate { _, key, _ -> key.name != "a" }

        @Test
        fun empty() {
            assertEquals(KeyTree.empty, KeyTree.empty.filter(FilterPredicate.KeepAll))
            assertEquals(KeyTree.empty, KeyTree.empty.filter(FilterPredicate.DropAll))
        }

        @Test
        fun simple() {
            val a = KeyTree.build(schema) {
                field("Foo", key("a"))
                field("Foo", key("b"))
            }
            assertEquals(a, a.filter(FilterPredicate.KeepAll))
            assertEquals(KeyTree.empty, a.filter(FilterPredicate.DropAll))
            assertEquals(
                KeyTree.build(schema) {
                    field("Foo", key("b"))
                },
                a.filter(dropA),
            )
        }

        @Test
        fun `drop nested tree`() {
            val a = KeyTree.build(schema) {
                field("Foo", key("a")) {
                    field("Bar", key("c"))
                }
                field("Foo", key("b"))
            }
            assertEquals(
                KeyTree.build(schema) {
                    field("Foo", key("b"))
                },
                a.filter(dropA),
            )
        }

        @Test
        fun `recursive filter`() {
            val a = KeyTree.build(schema) {
                field("Foo", key("b")) {
                    field("Bar", key("a"))
                    field("Bar", key("b"))
                }
                field("Foo", key("c")) {
                    field("Bar", key("a"))
                    field("Bar", key("b"))
                }
            }
            assertEquals(
                KeyTree.build(schema) {
                    field("Foo", key("b")) {
                        field("Bar", key("b"))
                    }
                    field("Foo", key("c")) {
                        field("Bar", key("b"))
                    }
                },
                a.filter(dropA)
            )
        }
    }

    @Nested
    inner class KeyTreeFilter {
        @Test
        fun and() {
            val tree = KeyTree.build(schema) {
                field("Foo", key("a"))
            }

            assertEquals(tree, tree.filter(FilterPredicate.KeepAll and FilterPredicate.KeepAll))
            assertEquals(KeyTree.empty, tree.filter(FilterPredicate.KeepAll and FilterPredicate.DropAll))
            assertEquals(KeyTree.empty, tree.filter(FilterPredicate.DropAll and FilterPredicate.KeepAll))
            assertEquals(KeyTree.empty, tree.filter(FilterPredicate.DropAll and FilterPredicate.DropAll))
        }

        @Test
        fun or() {
            val tree = KeyTree.build(schema) {
                field("Foo", key("a"))
            }

            assertEquals(tree, tree.filter(FilterPredicate.KeepAll or FilterPredicate.KeepAll))
            assertEquals(tree, tree.filter(FilterPredicate.KeepAll or FilterPredicate.DropAll))
            assertEquals(tree, tree.filter(FilterPredicate.DropAll or FilterPredicate.KeepAll))
            assertEquals(KeyTree.empty, tree.filter(FilterPredicate.DropAll or FilterPredicate.DropAll))
        }
    }

    @Nested
    inner class WrappedIn {
        @Test
        fun `empty`() {
            assertEquals(
                KeyTree.build(schema) {
                    field("Foo", key("a"))
                },
                KeyTree.empty.wrappedIn(fooType, ObjectEngineResult.Key("a"))
            )
        }

        @Test
        fun `nested`() {
            val a = KeyTree.build(schema) {
                field("Bar", key("c"))
            }
            assertEquals(
                KeyTree.build(schema) {
                    field("Foo", key("a")) {
                        field("Bar", key("c"))
                    }
                },
                a.wrappedIn(fooType, ObjectEngineResult.Key("a"))
            )
        }
    }
}
