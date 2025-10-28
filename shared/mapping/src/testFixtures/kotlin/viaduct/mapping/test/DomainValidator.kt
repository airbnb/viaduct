@file:Suppress("ForbiddenImport")

package viaduct.mapping.test

import graphql.schema.GraphQLSchema
import io.kotest.property.Arb
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.take
import kotlinx.coroutines.runBlocking
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.flatten
import viaduct.arbitrary.common.randomSource
import viaduct.arbitrary.graphql.GenInterfaceStubsIfNeeded
import viaduct.arbitrary.graphql.graphQLSchema
import viaduct.mapping.graphql.Conv
import viaduct.mapping.graphql.Domain
import viaduct.mapping.graphql.IR
import viaduct.utils.collections.None
import viaduct.utils.collections.Option
import viaduct.utils.collections.Some

const val DEFAULT_ITER = 1_000

/** A fixture to check that a [Domain] can roundtrip values */
class DomainValidator<From, To> private constructor(
    private val fromGen: Arb<From>,
    private val conv: Conv<From, To>,
    private val equals: Equals<From>,
) {
    /**
     * Check that arbitrarily-generated objects can be roundtripped through the domain
     * @param iter the maximum number of arbitrary objects for which roundtripping is attempted
     * @throws ValueRoundtripError if a value is found that could not be roundtripped
     */
    fun checkAll(iter: Int = DEFAULT_ITER) {
        checkAll(fromGen, iter)
    }

    private fun checkAll(
        valueGen: Arb<From>,
        iter: Int
    ) {
        runBlocking {
            val random = randomSource()
            valueGen.take(iter, random)
                .forEach { from ->
                    checkOrThrow(from, random.seed)
                }
        }
    }

    /**
     * Check that a single object can be roundtripped through the domain
     * @throws ValueRoundtripError if [value] cannot be roundtripped through the domain conv
     */
    fun check(value: From) = checkOrThrow(value)

    private fun checkOrThrow(
        expected: From,
        seed: Long? = null
    ) {
        tryRoundtrip(expected)
            .recover { err ->
                throw RoundtripError(expected, err, seed)
            }
            .map { actualOpt ->
                actualOpt.forEach { actual ->
                    throw ValueRoundtripError(expected, actual, seed)
                }
            }
    }

    /**
     * Try to roundtri [value]
     * @return [None] if value was successfully roundtripped, or [Some] of the roundtripped value if roundtripping failed
     */
    private fun tryRoundtrip(value: From): Result<Option<From>> =
        runCatching {
            conv.invert(conv(value))
        }.map { value2 ->
            if (!equals(value, value2)) {
                Some(value2)
            } else {
                None
            }
        }

    companion object {
        /**
         * Create a [DomainValidator] for a [Domain].
         * The returned [DomainValidator] will generate arbitrary objects using [Arb.Companion.objectIR].
         * For greater control over the object generator used for validation, see other overloads of this method.
         *
         * @param domain a test Domain through which values will be roundtripped
         * @param schema a schema from which test objects may be generated from
         * @param cfg configuration options to shape the generation of test objects
         * @param equalsFn a function to check the equality of roundtripped objects.
         *   If null, equality will be checked using `==`
         */
        operator fun <From> invoke(
            domain: Domain<From>,
            schema: GraphQLSchema,
            cfg: Config = Config.default,
            equalsFn: Function2<From, From, Boolean>? = null,
        ): DomainValidator<From, IR.Value.Object> =
            DomainValidator(
                Arb.objectIR(schema, cfg).map(domain.objectToIR().inverse()),
                domain.objectToIR(),
                Equals(equalsFn),
            )

        /**
         * Create a [DomainValidator] for a [Domain]
         * The returned [DomainValidator] will generate arbitrary objects using the supplied [generator].
         *
         * @param domain a test Domain through which object values will be roundtripped
         * @param generator a generator for object values to be roundtripped through [domain]
         * @param equalsFn a function to check the equality of roundtripped objects.
         *   If null, equality will be checked using `==`
         */
        operator fun <From> invoke(
            domain: Domain<From>,
            generator: Arb<From>,
            equalsFn: Function2<From, From, Boolean>? = null,
        ): DomainValidator<From, IR.Value.Object> =
            DomainValidator(
                generator,
                domain.objectToIR(),
                Equals(equalsFn),
            )

        private fun mkValueGen(schemaGen: Arb<GraphQLSchema>): Arb<IR.Value.Object> =
            schemaGen.map { schema ->
                Arb.objectIR(schema)
                    .take(100, randomSource())
                    .toList()
            }.flatten()

        private fun mkSchemaGen(schema: GraphQLSchema?): Arb<GraphQLSchema> =
            schema?.let(Arb.Companion::constant)
                ?: run {
                    val initialConfig = Config.default + (
                        // Any type that refers to an interface needs to be able to materialize an implementation of that
                        // interface. Ensure that the generated schema has at least one implementation for all interface types.
                        GenInterfaceStubsIfNeeded to true
                    )
                    Arb.graphQLSchema(initialConfig)
                }

        private fun interface Equals<T> : Function2<T, T, Boolean> {
            class Natural<T> : Equals<T> {
                override fun invoke(
                    a: T,
                    b: T
                ) = a == b
            }

            class Fn<T>(val fn: Function2<T, T, Boolean>) : Equals<T> {
                override fun invoke(
                    a: T,
                    b: T
                ): Boolean = fn(a, b)
            }

            companion object {
                operator fun <T> invoke(fn: Function2<T, T, Boolean>? = null): Equals<T> = fn?.let(::Fn) ?: Natural()
            }
        }
    }
}

/**
 * A naive Comparator that allows sorting IR.Value.Object's by the length of their
 * toString result.
 */
private object MinObject : Comparator<IR.Value.Object> {
    override fun compare(
        o1: IR.Value.Object,
        o2: IR.Value.Object
    ): Int = o1.toString().length.compareTo(o2.toString().length)
}

class ValueRoundtripError(
    val expected: Any?,
    val actual: Any?,
    val seed: Long? = null
) : Exception() {
    override val message: String get() {
        val seedStr = seed?.let { " (seed: $it)" } ?: ""
        val msg = "Domain failed to roundtrip value$seedStr. Expected $expected but got $actual"
        return msg
    }
}

class RoundtripError(
    val expected: Any?,
    override val cause: Throwable,
    val seed: Long? = null
) : Exception(cause) {
    override val message: String get() {
        val seedStr = seed?.let { " (seed: $it)" } ?: ""
        return "Failed to roundtrip value ${expected}$seedStr"
    }
}
