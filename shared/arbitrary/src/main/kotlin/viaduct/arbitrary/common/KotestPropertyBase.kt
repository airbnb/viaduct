package viaduct.arbitrary.common

import io.kotest.property.PropertyTesting
import io.kotest.property.RandomSource
import kotlin.random.Random
import viaduct.apiannotations.TestingApi

/**
 * Abstract base class for kotest-property test suites
 *
 * @param seed override the testing seed. This is useful for debugging property test failures
 * To preserve the randomness of property testing, subclasses should not permanently override
 * the seed value.
 */
@TestingApi
abstract class KotestPropertyBase(
    private val seed: Long = Random.nextLong()
) {
    init {
        // NB: values set on PropertyTesting are global and apply to all test suites run in the
        // current process. Be careful about setting properties that could influence test outcomes,
        // such as `defaultIterationCount` -- these should be configured on a per-test basis in the
        // form of parameters passed to forAll/forNone/checkAll
        //
        // Example:
        //   Arb.constant(true).forAll(iterations = 1_000_000) { it }
        PropertyTesting.defaultSeed = seed
    }

    val randomSource: RandomSource get() = randomSource()
}

@TestingApi
fun randomSource(): RandomSource = PropertyTesting.defaultSeed?.let(RandomSource::seeded) ?: RandomSource.default()
