@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.execution

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(1, jvmArgsAppend = ["-Xss16m"])
@Warmup(iterations = 3)
@Measurement(iterations = 5)
open class IndexBenchmark {
    @State(Scope.Benchmark)
    open class IndexState {
        lateinit var singleIndex: Index<Key, Value>
        lateinit var singleLookupKey: Key
        lateinit var thousandItemIndex: Index<Key, Value>
        lateinit var thousandItemLookupKey: Key
        lateinit var compositeIndex: Index<Key, Value>
        lateinit var compositeLookupKey: Key
        lateinit var mergeBaseIndex: Index<Key, Value>
        lateinit var mergeOverridesIndex: Index<Key, Value>

        private val thousandItemCount = 1000

        @Setup
        fun setup() {
            singleIndex = Index.single(Key(0), Value(0))
            singleLookupKey = Key(0)

            thousandItemIndex = buildIndex(start = 0, count = thousandItemCount)
            thousandItemLookupKey = Key(thousandItemCount - 1)

            mergeBaseIndex = buildIndex(start = 0, count = thousandItemCount)
            mergeOverridesIndex = buildIndex(start = thousandItemCount, count = thousandItemCount)
            compositeIndex = mergeBaseIndex.merge(mergeOverridesIndex)
            compositeLookupKey = Key(thousandItemCount + (thousandItemCount / 2))

            check(thousandItemIndex[thousandItemLookupKey] == Value(thousandItemCount - 1))
            check(compositeIndex[compositeLookupKey] == Value(thousandItemCount + (thousandItemCount / 2)))
        }
    }

    @Benchmark
    fun findInSingleIndex(
        state: IndexState,
        blackhole: Blackhole
    ) {
        blackhole.consume(state.singleIndex[state.singleLookupKey])
    }

    @Benchmark
    fun findInThousandItemIndex(
        state: IndexState,
        blackhole: Blackhole
    ) {
        blackhole.consume(state.thousandItemIndex[state.thousandItemLookupKey])
    }

    @Benchmark
    fun findInThousandPlusThousandCompositeIndex(
        state: IndexState,
        blackhole: Blackhole
    ) {
        blackhole.consume(state.compositeIndex[state.compositeLookupKey])
    }

    @Benchmark
    fun mergeThousandPlusThousandIndexes(
        state: IndexState,
        blackhole: Blackhole
    ) {
        blackhole.consume(state.mergeBaseIndex.merge(state.mergeOverridesIndex))
    }

    data class Key(val id: Int)

    data class Value(val id: Int)

    private companion object {
        fun buildIndex(
            start: Int,
            count: Int
        ): Index<Key, Value> {
            val builder = Index.builder<Key, Value>()
            for (id in start until start + count) {
                builder.add(Key(id), Value(id))
            }
            return builder.build()
        }
    }
}
