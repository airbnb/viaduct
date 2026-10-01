package viaduct.engine.runtime2.arbitrary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ResolverBenchmarkQueryCorpusTest {
    @Test
    fun `query corpus round trips its seed and ordered sources`() {
        val corpus =
            ResolverBenchmarkQueryCorpus.create(
                generationSeed = 17,
                querySources =
                    listOf(
                        "query { first }",
                        "query {\n  second\n}",
                    ),
            )

        val decoded = ResolverBenchmarkQueryCorpus.decode(corpus.encode())

        assertEquals(17, decoded.generationSeed)
        assertEquals(corpus.querySources, decoded.querySources)
    }

    @Test
    fun `query corpus rejects empty or blank sources`() {
        assertFailsWith<IllegalArgumentException> {
            ResolverBenchmarkQueryCorpus.create(1, emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            ResolverBenchmarkQueryCorpus.create(1, listOf("query { field }", " "))
        }
    }
}
