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
import viaduct.mapping.graphql.Domain
import viaduct.mapping.graphql.IR
import viaduct.utils.bijection.Bijection
import viaduct.utils.collections.None
import viaduct.utils.collections.Option
import viaduct.utils.collections.Some

const val DEFAULT_ITER = 10_000

/** A fixture to check that a [Domain] is bijective */
class DomainValidator<From, To> private constructor(
    private val fromGen: Arb<From>,
    private val bijection: Bijection<From, To>,
    private val equals: Equals<From>,
) {
    /**
     * Check that arbitrarily-generated objects can be roundtripped through the domain
     * @param iter the maximum number of arbitrary objects for which bijection is attempted
     * @throws DomainIsNotBijective if a bijection failure is found
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
            fromGen.take(iter, random)
                .forEach { from ->
                    checkOrThrow(from, random.seed)
                }
        }
    }

    /**
     * Check that a single object can be roundtripped through the domain
     * @throws DomainIsNotBijective if [value] cannot be roundtripped through the domain bijection
     */
    fun check(value: From) = checkOrThrow(value)

    private fun checkOrThrow(
        expected: From,
        seed: Long? = null
    ) {
        tryBiject(expected)
            .recover { err ->
                throw BijectionError(expected, err, seed)
            }
            .map { actualOpt ->
                actualOpt.forEach { actual ->
                    throw DomainIsNotBijective(expected, actual, seed)
                }
            }
    }

    /**
     * Try to biject [value]
     * @return [None] if value was successfully bijected, or [Some] of the roundtripped value if bijection failed
     */
    private fun tryBiject(value: From): Result<Option<From>> =
        runCatching {
            bijection.invert(bijection(value))
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

class DomainIsNotBijective(
    val expected: Any?,
    val actual: Any?,
    val seed: Long? = null
) : Exception() {
    override val message: String get() {
        val seedStr = seed?.let { " (seed: $it)" } ?: ""
        val msg = "Domain failed to biject value$seedStr. Expected $expected but got $actual"
        return msg
    }
}

class BijectionError(
    val expected: Any?,
    override val cause: Throwable,
    val seed: Long? = null
) : Exception(cause) {
    override val message: String get() {
        val seedStr = seed?.let { " (seed: $it)" } ?: ""
        return "Failed to biject value ${expected}$seedStr"
    }
}
