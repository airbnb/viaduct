package viaduct.engine.runtime2.schema

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import viaduct.engine.runtime2.model.SelectionForest

/** Includes a full occurrence walk so deferring all work cannot masquerade as eliminating it. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 30)
@Measurement(iterations = 3, time = 60)
@Fork(value = 2, jvmArgsAppend = ["-Xms2g", "-Xmx8g", "-XX:+UseG1GC"])
open class NormalizedDocumentBackedForestBenchmark {
    private val fixture = NormalizedDocumentSelectionBenchmark()

    @Setup(Level.Trial)
    fun setup() {
        fixture.setup()
        check(consume(fixture.convertNormalizedBacked()) == 187_215L)
    }

    /** Request preparation only; descendants deliberately remain unobserved. */
    @Benchmark
    fun startNormalized(): SelectionForest = fixture.convertNormalizedBacked()

    @Benchmark
    fun consumeAst(): Long = consume(fixture.convertAst())

    @Benchmark
    fun consumeNormalizedEager(): Long = consume(fixture.convertNormalized())

    @Benchmark
    fun consumeNormalizedShared(): Long = consume(fixture.convertNormalizedBacked())

    private fun consume(forest: SelectionForest): Long {
        var count = 0L
        forest.forEach { selection -> count += 1 + consume(selection.subselections) }
        return count
    }
}
