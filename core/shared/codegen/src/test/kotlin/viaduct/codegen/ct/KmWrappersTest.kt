@file:Suppress("ktlint:standard:filename", "MatchingDeclarationName")

package viaduct.codegen.ct

import kotlinx.metadata.KmClass
import kotlinx.metadata.KmFunction
import kotlinx.metadata.Modality
import kotlinx.metadata.modality
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KmClassTreeTest {
    private fun wrapper(name: String): KmClassWrapper =
        KmClassWrapper(
            KmClass().also { it.name = name }
        )

    private fun tree(
        name: String,
        children: (() -> List<KmClassTree>) = { emptyList() }
    ): KmClassTree = KmClassTree(wrapper(name), children())

    private fun label(
        tree: KmClassTree,
        other: KmClassTree?
    ): String = tree.cls.kmClass.name + ":" + (other?.cls?.kmClass?.name ?: "null")

    @Test
    fun flatten() {
        // single
        tree("a").also { a ->
            assertEquals(listOf(a.cls), a.flatten())
        }

        // one layer with multiple nested
        tree("a") {
            listOf(
                tree("b1"),
                tree("b2"),
                tree("b3")
            )
        }.also { a ->
            val flatNames = a.flatten().map { it.kmClass.name }
            assertEquals(listOf("a", "b1", "b2", "b3"), flatNames)
        }

        // deeply nested
        tree("a") {
            listOf(
                tree("b") {
                    listOf(tree("c"))
                }
            )
        }.let { a ->
            val flatNames = a.flatten().map { it.kmClass.name }
            assertEquals(listOf("a", "b", "c"), flatNames)
        }

        // fan
        tree("a") {
            listOf(
                tree("b1") {
                    listOf(tree("c11"), tree("c12"))
                },
                tree("b2") {
                    listOf(tree("c21"), tree("c22"))
                }
            )
        }.also { a ->
            val flatNames = a.flatten().map { it.kmClass.name }
            assertEquals(listOf("a", "b1", "c11", "c12", "b2", "c21", "c22"), flatNames)
        }
    }

    @Test
    fun mapWithOuter() {
        // single
        assertEquals(listOf("a:null"), tree("a").mapWithOuter(::label))

        // fan
        tree("a") {
            listOf(
                tree("b1") {
                    listOf(tree("c11"), tree("c12"))
                },
                tree("b2") {
                    listOf(tree("c21"), tree("c22"))
                }
            )
        }.let { a ->
            val labels = a.mapWithOuter(::label)
            assertEquals(
                listOf("a:null", "b1:a", "c11:b1", "c12:b1", "b2:a", "c21:b2", "c22:b2"),
                labels
            )
        }
    }

    @Test
    fun `Iterable_KmClassTree flatten`() {
        // empty
        assertTrue(emptyList<KmClassTree>().flatten().isEmpty())

        listOf(
            tree("a1") { listOf(tree("b11"), tree("b12")) },
            tree("a2") { listOf(tree("b21"), tree("b22")) }
        ).let { list ->
            val flatNames = list.flatten().map { it.kmClass.name }
            assertEquals(
                listOf("a1", "b11", "b12", "a2", "b21", "b22"),
                flatNames
            )
        }
    }

    @Test
    fun `Iterable_KmClassTree mapWithOuter`() {
        listOf(
            tree("a1") { listOf(tree("b11"), tree("b12")) },
            tree("a2") { listOf(tree("b21"), tree("b22")) }
        ).let { list ->
            val labels = list.mapWithOuter(::label)

            assertEquals(
                listOf("a1:null", "b11:a1", "b12:a1", "a2:null", "b21:a2", "b22:a2"),
                labels
            )
        }
    }

    @Test
    fun `KmFunctionWrapper throws on non-final bridged functions`() {
        Modality.values().forEach { modality ->
            val result =
                Result.runCatching {
                    KmFunctionWrapper(
                        fn = KmFunction("test").apply {
                            this.returnType = KM_UNIT_TYPE
                            this.modality = modality
                        },
                        bridgeParameters = setOf(-1)
                    )
                }

            if (modality == Modality.FINAL) {
                assertTrue(result.isSuccess)
            } else {
                assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            }
        }
    }
}
