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

/**
 * Measures Engine2 requests of the first plan-cache step through `StandardViaduct`.
 *
 * `planCache` toggles the `ENGINE2_PLAN_CACHE` flag. `documents=shared` gives every execution of
 * an operation the same cached document, as the service document cache does; `documents=fresh`
 * gives every execution an unseen document, isolating reuse within one request. One operation
 * executes the generated batch `loopCount` times.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 10)
@Fork(2)
@Timeout(time = 10, timeUnit = TimeUnit.MINUTES)
open class PlanCacheBenchmark {
    @JvmField
    @Param("off", "on")
    var planCache: String = "on"

    @JvmField
    @Param("shared", "fresh")
    var documents: String = "shared"

    @JvmField
    @Param("1")
    var loopCount: Int = 1

    @JvmField
    @Param("7")
    var seed: Long = 7

    @JvmField
    @Param("100")
    var queryCount: Int = 100

    /** Elements per generated list value; larger lists increase reuse within one request. */
    @JvmField
    @Param("2")
    var listSize: Int = 2

    /** `step1` or `step2`; step 2 adds FromArgument resolver variables and operation `@skip`/`@include`. */
    @JvmField
    @Param("step1")
    var profile: String = "step1"

    /** For step 2: `fixed` directive values, or `rotating` through all four combinations. */
    @JvmField
    @Param("fixed")
    var directiveValues: String = "fixed"

    private lateinit var support: PlanCacheBenchmarkSupport
    private var profileRecording: Recording? = null

    @Setup(Level.Trial)
    fun prepareTrial() {
        val workload = PlanCacheWorkload.generate(seed, queryCount, listSize, PlanCacheProfile.valueOf(profile.uppercase(Locale.ROOT)))
        val mode = PlanCacheDocuments.valueOf(documents.uppercase(Locale.ROOT))
        val values = PlanCacheDirectiveValues.valueOf(directiveValues.uppercase(Locale.ROOT))
        support = PlanCacheBenchmarkSupport(workload, planCache = planCache == "on", documents = mode, directiveValues = values)
        if (planCache == "on") {
            val reference = PlanCacheBenchmarkSupport(workload, planCache = false, documents = mode, directiveValues = values)
            check(reference.operations == support.operations) { "Repaired operations differ between engines" }
            val differing = support.responses.indices.filter { support.responses[it]["data"] != reference.responses[it]["data"] }
            check(differing.isEmpty()) { "Plan-cache responses differ from uncached responses at operations $differing" }
            println("PlanCacheBenchmark: cached and uncached data agree for ${support.responses.size} operations")
        }
        val sizes = support.responses.map(::planCacheResponseSize)
        val errorResponses = support.responses.count { (it["errors"] as? List<*>)?.isNotEmpty() == true }
        println(
            "PlanCacheBenchmark workload profile=$profile seed=$seed listSize=$listSize: operations=${support.operations.size}/$queryCount, " +
                "fields avg=${sizes.map { it.first }.average().format()} max=${sizes.maxOf { it.first }}, " +
                "objects avg=${sizes.map { it.second }.average().format()} max=${sizes.maxOf { it.second }}, " +
                "responses with errors=$errorResponses, FromArgument variables=${workload.corpus.registry.features.fromArgumentVariableCount}",
        )
    }

    @Setup(Level.Invocation)
    fun prepareInvocation(iteration: IterationParams) {
        support.prepareInvocation(loopCount)
        val output = System.getProperty("planCacheProfileOutput") ?: return
        if (iteration.type != IterationType.MEASUREMENT) return
        val destination = Path.of(output).toAbsolutePath()
        destination.parent?.let(Files::createDirectories)
        Files.deleteIfExists(destination)
        profileRecording = Recording(Configuration.getConfiguration("profile")).apply {
            name = "plan-cache-$profile-$seed-$planCache-$documents"
            setDestination(destination)
            start()
        }
    }

    /** Records only the measured invocation when `planCacheProfileOutput` names a JFR destination. */
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

private fun Double.format(): String = String.format(Locale.ROOT, "%.1f", this)
