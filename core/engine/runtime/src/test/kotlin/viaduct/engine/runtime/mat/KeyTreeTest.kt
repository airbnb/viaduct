package viaduct.engine.runtime.mat

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.arbitrary.graphql.asViaductSchema
import viaduct.engine.runtime.mat.KeyTreeFilter as FilterPredicate
import viaduct.engine.runtime.result.ObjectEngineResult

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
    private val barType = checkNotNull(schema.schema.getObjectType("Bar"))

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
    inner class StringRepresentation {
        @Test
        fun `uses type names and preserves nested keys`() {
            val tree = KeyTree.build(schema) {
                field("Foo", key("c", alias = "result", arguments = mapOf("a" to 1))) {
                    field("Bar", key("b"))
                }
            }

            assertEquals(
                "KeyTree(Foo={Key(name='c', alias='result', arguments=a=1)=" +
                    "KeyTree(Bar={Key(name='b', alias='null', arguments=)=KeyTree()})})",
                tree.toString(),
            )
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
            tree.responseKeysForType(fooType).shouldContainExactlyInAnyOrder("a")
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
            tree.responseKeysForType(fooType).shouldContainExactlyInAnyOrder("a")
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
        fun `preserves empty type branches`() {
            val typedEmpty = KeyTree(mapOf(fooType to emptyMap()))

            assertFalse(typedEmpty == KeyTree.empty)
            assertEquals(setOf(fooType), typedEmpty.keysByType().keys)
            assertTrue(typedEmpty.keysByType().getValue(fooType).isEmpty())
        }
    }

    @Nested
    inner class SubtreeAt {
        @Test
        fun `root path returns the complete tree`() {
            val tree = KeyTree.build(schema) {
                field("Foo", key("a"))
                field("Foo", key("b")) {
                    field("Bar", key("a"))
                }
            }

            assertEquals(tree, tree.subtreeAt(MatPath(fooType)))
        }

        @Test
        fun `nested path returns the terminal subtree`() {
            val pathSchema =
                """
                | type Root { child: Child }
                | type Child { grandchild: Grandchild }
                | type Grandchild { value: String }
                """.trimMargin().asViaductSchema
            val rootType = checkNotNull(pathSchema.schema.getObjectType("Root"))
            val childType = checkNotNull(pathSchema.schema.getObjectType("Child"))
            val grandchildType = checkNotNull(pathSchema.schema.getObjectType("Grandchild"))
            val childKey = ObjectEngineResult.Key("child")
            val grandchildKey = ObjectEngineResult.Key("grandchild")
            val expected = KeyTree.build(pathSchema) {
                field("Grandchild", key("value"))
            }
            val tree = KeyTree.build(pathSchema) {
                field("Root", childKey) {
                    field("Child", grandchildKey) {
                        field("Grandchild", key("value"))
                    }
                }
            }
            val path =
                MatPath(
                    rootType,
                    listOf(
                        MatPath.Segment(childType, childKey),
                        MatPath.Segment(grandchildType, grandchildKey),
                    ),
                )

            assertEquals(expected, tree.subtreeAt(path))
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
        fun `empty type branches are not empty trees`() {
            assertFalse(KeyTree(mapOf(fooType to emptyMap())).isEmpty())
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
        fun `preserves empty type branch when unioned with empty tree`() {
            val typedEmpty = KeyTree(mapOf(fooType to emptyMap()))

            assertEquals(typedEmpty, typedEmpty + KeyTree.empty)
            assertEquals(typedEmpty, KeyTree.empty + typedEmpty)
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
    inner class Intersect {
        @Test
        fun empty() {
            val foo = KeyTree.build(schema) {
                field("Foo", key("x"))
            }

            assertEquals(KeyTree.empty, KeyTree.empty.intersect(KeyTree.empty))
            assertEquals(KeyTree.empty, foo.intersect(KeyTree.empty))
            assertEquals(KeyTree.empty, KeyTree.empty.intersect(foo))
        }

        @Test
        fun `empty type branch intersects a populated branch of the same type`() {
            val typedEmpty = KeyTree(mapOf(fooType to emptyMap()))
            val foo = KeyTree.build(schema) {
                field("Foo", key("x"))
            }

            assertEquals(KeyTree.empty, typedEmpty.intersect(KeyTree.empty))
            assertEquals(typedEmpty, typedEmpty.intersect(foo))
            assertEquals(typedEmpty, foo.intersect(typedEmpty))
        }

        @Test
        fun self() {
            val tree = KeyTree.build(schema) {
                field("Foo", key("a"))
                field("Foo", key("x")) {
                    field("Bar", key("b"))
                }
            }

            assertSame(tree, tree.intersect(tree))
        }

        @Test
        fun `equal trees reuse the receiver`() {
            val tree = KeyTree.build(schema) {
                field("Foo", key("a"))
                field("Foo", key("x")) {
                    field("Bar", key("b"))
                }
            }
            val equalTree = KeyTree.build(schema) {
                field("Foo", key("a"))
                field("Foo", key("x")) {
                    field("Bar", key("b"))
                }
            }

            assertSame(tree, tree.intersect(equalTree))
        }

        @Test
        fun disjoint() {
            val a = KeyTree.build(schema) {
                field("Foo", key("a"))
            }
            val b = KeyTree.build(schema) {
                field("Foo", key("b"))
            }

            assertEquals(KeyTree(mapOf(fooType to emptyMap())), a.intersect(b))
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
                    field("Foo", key("a"))
                },
                a.intersect(b),
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

            assertEquals(KeyTree(mapOf(fooType to emptyMap())), a.intersect(b))
        }

        @Test
        fun argumented() {
            val a = KeyTree.build(schema) {
                field("Foo", key("x", arguments = mapOf("id" to 1)))
            }
            val b = KeyTree.build(schema) {
                field("Foo", key("x", arguments = mapOf("id" to 2)))
            }

            assertEquals(KeyTree(mapOf(fooType to emptyMap())), a.intersect(b))
        }

        @Test
        fun `fields are scoped by concrete type`() {
            val a = KeyTree.build(schema) {
                field("Foo", key("a"))
                field("Bar", key("a"))
            }
            val b = KeyTree.build(schema) {
                field("Foo", key("b"))
                field("Bar", key("a"))
            }

            assertEquals(
                KeyTree(mapOf(fooType to emptyMap())) +
                    KeyTree.build(schema) {
                        field("Bar", key("a"))
                    },
                a.intersect(b),
            )
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
                    field("Bar", key("b"))
                    field("Bar", key("c"))
                }
            }

            assertEquals(
                KeyTree.build(schema) {
                    field("Foo", key("x")) {
                        field("Bar", key("b"))
                    }
                },
                a.intersect(b),
            )
        }

        @Test
        fun `common parent with disjoint children`() {
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
            val expected = KeyTree(
                mapOf(
                    fooType to mapOf(
                        ObjectEngineResult.Key("x") to KeyTree(mapOf(barType to emptyMap()))
                    )
                )
            )

            assertEquals(expected, a.intersect(b))
            assertEquals(expected, b.intersect(a))
        }

        @Test
        fun `common leaf excludes children`() {
            val leaf = KeyTree.build(schema) {
                field("Foo", key("x"))
            }
            val nested = KeyTree.build(schema) {
                field("Foo", key("x")) {
                    field("Bar", key("a"))
                }
            }

            assertEquals(leaf, leaf.intersect(nested))
            assertEquals(leaf, nested.intersect(leaf))
        }

        @Test
        fun commutative() {
            val a = KeyTree.build(schema) {
                field("Foo", key("a"))
                field("Foo", key("x")) {
                    field("Bar", key("a"))
                    field("Bar", key("b"))
                }
                field("Bar", key("c"))
            }
            val b = KeyTree.build(schema) {
                field("Foo", key("b"))
                field("Foo", key("x")) {
                    field("Bar", key("b"))
                    field("Bar", key("c"))
                }
                field("Bar", key("c"))
            }

            assertEquals(a.intersect(b), b.intersect(a))
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
        fun `empty type branches participate in subtraction`() {
            val typedEmpty = KeyTree(mapOf(fooType to emptyMap()))
            val foo = KeyTree.build(schema) {
                field("Foo", key("a"))
            }

            assertEquals(typedEmpty, typedEmpty - KeyTree.empty)
            assertEquals(KeyTree.empty, KeyTree.empty - typedEmpty)
            assertEquals(KeyTree.empty, typedEmpty - typedEmpty)
            assertEquals(foo, foo - typedEmpty)
            assertEquals(KeyTree.empty, typedEmpty - foo)
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
            KeyTree.empty.responseKeysForType(fooType).shouldBeEmpty()
        }

        @Test
        fun `simple`() {
            KeyTree.build(schema) {
                field("Foo", key("a"))
                field("Foo", key("b"))
            }.responseKeysForType(fooType)
                .shouldContainExactlyInAnyOrder("a", "b")
        }

        @Test
        fun `aliased`() {
            KeyTree.build(schema) {
                field("Foo", key("a", alias = "b"))
                field("Foo", key("b", alias = "c"))
            }.responseKeysForType(fooType)
                .shouldContainExactlyInAnyOrder("b", "c")
        }

        @Test
        fun `argumented`() {
            KeyTree.build(schema) {
                field("Foo", key("c", arguments = mapOf("a" to 1)))
            }.responseKeysForType(fooType)
                .shouldContainExactlyInAnyOrder("c")
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
            assertEquals(KeyTree(mapOf(fooType to emptyMap())), a.filter(FilterPredicate.DropAll))
            assertEquals(
                KeyTree.build(schema) {
                    field("Foo", key("b"))
                },
                a.filter(dropA),
            )
        }

        @Test
        fun `preserves nested type branch when all of its fields are dropped`() {
            val tree = KeyTree.build(schema) {
                field("Foo", key("b")) {
                    field("Bar", key("a"))
                }
            }
            val expected = KeyTree(
                mapOf(
                    fooType to mapOf(
                        ObjectEngineResult.Key("b") to KeyTree(mapOf(barType to emptyMap()))
                    )
                )
            )

            assertEquals(expected, tree.filter(dropA))
        }

        @Test
        fun KeepAll() {
            val a = KeyTree.build(schema) {
                field("Foo", key("a"))
            }
            val filtered = a.filter(viaduct.engine.runtime.mat.KeyTreeFilter.KeepAll)
            assertSame(a, filtered)
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
    inner class WithoutEmptyTypeBranches {
        @Test
        fun `empty tree remains empty`() {
            assertEquals(KeyTree.empty, KeyTree.empty.withoutEmptyTypeBranches())
        }

        @Test
        fun `removes empty root type branch`() {
            val tree = KeyTree(mapOf(fooType to emptyMap()))

            assertEquals(KeyTree.empty, tree.withoutEmptyTypeBranches())
        }

        @Test
        fun `keeps populated sibling type branch`() {
            val tree = KeyTree(
                mapOf(
                    fooType to emptyMap(),
                    barType to mapOf(ObjectEngineResult.Key("a") to KeyTree.empty),
                )
            )

            assertEquals(
                KeyTree.build(schema) {
                    field("Bar", key("a"))
                },
                tree.withoutEmptyTypeBranches(),
            )
        }

        @Test
        fun `keeps parent field when its empty child branch is removed`() {
            val tree = KeyTree(
                mapOf(
                    fooType to mapOf(
                        ObjectEngineResult.Key("b") to KeyTree(mapOf(barType to emptyMap()))
                    ),
                )
            )

            assertEquals(
                KeyTree.build(schema) {
                    field("Foo", key("b"))
                },
                tree.withoutEmptyTypeBranches(),
            )
        }

        @Test
        fun `removes deeply nested empty type branch`() {
            val tree = KeyTree(
                mapOf(
                    fooType to mapOf(
                        ObjectEngineResult.Key("b") to KeyTree(
                            mapOf(
                                barType to mapOf(
                                    ObjectEngineResult.Key("a") to
                                        KeyTree(mapOf(fooType to emptyMap()))
                                )
                            )
                        )
                    )
                )
            )

            assertEquals(
                KeyTree.build(schema) {
                    field("Foo", key("b")) {
                        field("Bar", key("a"))
                    }
                },
                tree.withoutEmptyTypeBranches(),
            )
        }

        @Test
        fun `tree without empty type branches is unchanged`() {
            val tree = KeyTree.build(schema) {
                field("Foo", key("a"))
                field("Foo", key("b")) {
                    field("Bar", key("a"))
                }
            }

            assertEquals(tree, tree.withoutEmptyTypeBranches())
        }
    }

    @Nested
    inner class KeyTreeFilter {
        @Test
        fun and() {
            val tree = KeyTree.build(schema) {
                field("Foo", key("a"))
            }
            val emptyFooBranch = KeyTree(mapOf(fooType to emptyMap()))

            assertEquals(tree, tree.filter(FilterPredicate.KeepAll and FilterPredicate.KeepAll))
            assertEquals(emptyFooBranch, tree.filter(FilterPredicate.KeepAll and FilterPredicate.DropAll))
            assertEquals(emptyFooBranch, tree.filter(FilterPredicate.DropAll and FilterPredicate.KeepAll))
            assertEquals(emptyFooBranch, tree.filter(FilterPredicate.DropAll and FilterPredicate.DropAll))
        }

        @Test
        fun or() {
            val tree = KeyTree.build(schema) {
                field("Foo", key("a"))
            }
            val emptyFooBranch = KeyTree(mapOf(fooType to emptyMap()))

            assertEquals(tree, tree.filter(FilterPredicate.KeepAll or FilterPredicate.KeepAll))
            assertEquals(tree, tree.filter(FilterPredicate.KeepAll or FilterPredicate.DropAll))
            assertEquals(tree, tree.filter(FilterPredicate.DropAll or FilterPredicate.KeepAll))
            assertEquals(emptyFooBranch, tree.filter(FilterPredicate.DropAll or FilterPredicate.DropAll))
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
