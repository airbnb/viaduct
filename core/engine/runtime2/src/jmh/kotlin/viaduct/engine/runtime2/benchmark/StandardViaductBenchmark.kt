package viaduct.engine.runtime2.benchmark

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.TimeUnit
import jdk.jfr.Configuration
import jdk.jfr.Recording
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Timeout
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import org.openjdk.jmh.infra.IterationParams
import org.openjdk.jmh.runner.IterationType

@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 3)
@Fork(1)
@Timeout(time = 10, timeUnit = TimeUnit.MINUTES)
open class StandardViaductBenchmark {
    @JvmField
    @Param("old", "new")
    var engine: String = "new"

    @JvmField
    @Param("1")
    var loopCount: Int = 1

    @JvmField
    @Param("enabled")
    var nodeCache: String = "enabled"

    @JvmField
    @Param("deferred")
    var cycleChecker: String = "deferred"

    private lateinit var support: StandardViaductBenchmarkSupport
    private var profileRecording: Recording? = null

    @Setup(Level.Trial)
    fun prepareTrial() {
        val selectedEngine = BenchmarkEngine.valueOf(engine.uppercase(Locale.ROOT))
        support = StandardViaductBenchmarkSupport(
            selectedEngine,
            nodeCache = BenchmarkNodeCache.parse(nodeCache),
            cycleChecker = BenchmarkCycleChecker.parse(cycleChecker),
        )
    }

    @Setup(Level.Invocation)
    fun prepareInvocation(iteration: IterationParams) {
        support.prepareInvocation(loopCount)
        val output = System.getProperty("standardViaductProfileOutput") ?: return
        if (iteration.type != IterationType.MEASUREMENT) return
        val destination = Path.of(output).toAbsolutePath()
        destination.parent?.let(Files::createDirectories)
        Files.deleteIfExists(destination)
        profileRecording = Recording(Configuration.getConfiguration("profile")).apply {
            name = "standard-viaduct-$engine-$nodeCache-$cycleChecker"
            setDestination(destination)
            start()
        }
    }

    @TearDown(Level.Invocation)
    fun stopProfile() {
        profileRecording?.let { recording ->
            profileRecording = null
            recording.stop()
            recording.close()
        }
    }

    @Benchmark
    fun execute(blackhole: Blackhole): Int = support.executeBatch(blackhole::consume)
}
