@file:Suppress("EnumNaming", "VariableNaming")

package viaduct.utils.collections

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 3)
@Measurement(iterations = 3)
@ExperimentalCoroutinesApi
open class MaskedSetBenchmark {
    private lateinit var data_0_1000: Data
    private lateinit var data_0_100: Data
    private lateinit var data_0_10: Data

    private fun testIntersect(
        left: Data,
        right: Data
    ): Any =
        when (type) {
            Type.baseline -> left.set.intersect(right.set)
            Type.maskedSet -> left.maskedSet.intersect(right.maskedSet)
        }

    private class Data(
        val set: Set<Int>,
        val maskedSet: MaskedSet<Int>
    ) {
        constructor(range: IntRange) : this(range.toSet(), MaskedSet(range.toSet()))
    }

    enum class Type { baseline, maskedSet }

    @Param
    lateinit var type: Type

    @Setup
    fun setup() {
        data_0_1000 = Data(0 until 1000)
        data_0_100 = Data(0 until 100)
        data_0_10 = Data(0 until 10)
    }

    @Benchmark
    fun `intersect_1000x100`(bh: Blackhole) {
        bh.consume(testIntersect(data_0_1000, data_0_100))
    }

    @Benchmark
    fun `intersect_100x10`(bh: Blackhole) {
        bh.consume(testIntersect(data_0_100, data_0_10))
    }
}
