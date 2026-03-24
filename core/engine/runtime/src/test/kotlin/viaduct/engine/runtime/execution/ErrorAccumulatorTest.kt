package viaduct.engine.runtime.execution

import graphql.ExceptionWhileDataFetching
import graphql.GraphQLError
import graphql.execution.ResultPath
import graphql.language.SourceLocation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.runtime.execution.ExecutionTestHelpers.runExecutionTest
import viaduct.utils.collections.parallelMap

class ErrorAccumulatorTest {
    private fun mkError(path: String): GraphQLError =
        ExceptionWhileDataFetching(
            ResultPath.parse(path),
            RuntimeException(),
            SourceLocation.EMPTY
        )

    private val err1 = mkError("/x")
    private val err2 = mkError("/y")

    @Test
    fun `isEmpty`() {
        assertTrue(ErrorAccumulator().isEmpty())
        assertFalse(ErrorAccumulator().also { it += err1 }.isEmpty())
    }

    @Test
    fun `add -- distinct errors`() {
        val acc = ErrorAccumulator()
        acc.add(err1)
        acc.add(err2)
        assertEquals(listOf(err1, err2), acc.toList())
    }

    @Test
    fun `add -- repeated errors`() {
        val acc = ErrorAccumulator()
        acc.add(err1)
        acc.add(err1)
        assertTrue(acc.toList() == listOf(err1))
    }

    @Test
    fun `addAll -- empty`() {
        val acc = ErrorAccumulator()
        acc.addAll(emptyList())
        assertTrue(acc.isEmpty())
    }

    @Test
    fun `addAll -- distinct errors`() {
        val acc = ErrorAccumulator()
        acc.addAll(listOf(err1, err2))
        assertTrue(acc.toList() == listOf(err1, err2))
    }

    @Test
    fun `addAll -- repeated errors`() {
        val acc = ErrorAccumulator()
        acc.addAll(listOf(err1, err1))
        assertTrue(acc.toList() == listOf(err1))
    }

    @Test
    fun `plusAssign -- single distinct`() {
        val acc = ErrorAccumulator()
        acc += err1
        acc += err2
        assertTrue(acc.toList() == listOf(err1, err2))
    }

    @Test
    fun `plusAssign -- single repeated`() {
        val acc = ErrorAccumulator()
        acc += err1
        acc += err1
        assertTrue(acc.toList() == listOf(err1))
    }

    @Test
    fun `plusAssign -- multiple distinct`() {
        val acc = ErrorAccumulator()
        acc += listOf(err1, err2)
        assertTrue(acc.toList() == listOf(err1, err2))
    }

    @Test
    fun `plusAssign -- multiple repeated`() {
        val acc = ErrorAccumulator()
        acc += listOf(err1, err1)
        assertTrue(acc.toList() == listOf(err1))
    }

    @Test
    fun `toList is immutable`() {
        val acc = ErrorAccumulator()
        // sanity
        assertTrue(acc.isEmpty())

        // add an error to returned list
        acc.toList().let { it + err1 }

        // accumulator should be unmodified
        assertTrue(acc.isEmpty())
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `add is threadsafe`() {
        runExecutionTest {
            // test that ErrorAccumulator can be modified by multiple threads, adding all
            // errors at distinct paths and never adding multiple errors at the same path

            val distinctPaths = 5_000
            val repeatedPathCount = 3

            val acc = ErrorAccumulator()
            val paths = (1..distinctPaths).flatMap { i ->
                List(repeatedPathCount) {
                    ResultPath.rootPath().segment(i)
                }
            }.shuffled()

            // sanity
            assertEquals(distinctPaths * repeatedPathCount, paths.size)

            paths.parallelMap(10, 10) { path ->
                val err = GraphQLError.newError().message("err").path(path).build()
                acc.add(err)
            }.collect()

            val countByPath = acc.toList()
                .groupBy { it.path.toString() }
                .mapValues { (_, vs) -> vs.count() }

            assertEquals(distinctPaths, countByPath.size)
            assertEquals(setOf(1), countByPath.values.toSet())
        }
    }

    @Test
    fun `toList returns errors in sorted order`() {
        val acc = ErrorAccumulator()
        val errors = (1..1_000).map { i ->
            val label = "%04d".format(i)
            val path = ResultPath.rootPath().segment(label)
            GraphQLError.newError().message(label).path(path).build()
        }

        errors.shuffled().forEach { acc.add(it) }

        // avoid using assertEquals here, as a failure message will print out the expected
        // and actual collections which will be too long to be useful
        assertEquals(errors, acc.toList())
    }
}
