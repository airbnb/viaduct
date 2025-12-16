package viaduct.arbitrary.graphql

import graphql.TypeResolutionEnvironment
import graphql.execution.MergedField
import graphql.execution.ResultPath
import graphql.language.Field
import graphql.language.SelectionSet
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLTypeUtil
import graphql.schema.GraphQLUnionType
import graphql.schema.idl.RuntimeWiring
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.next
import io.kotest.property.arbitrary.of
import viaduct.arbitrary.common.Config

/**
 * Create a RuntimeWiring that serves arbitrary data for a schema.
 *
 * Though the data returned by this wiring is arbitrarily generated, the wiring
 * guarantees that for a given [seed] value it will always return the same data for a
 * given graphql document.
 *
 * The properties of the generated values can be configured in [cfg]. Supported [ConfigKey]'s
 * are:
 * - [ExplicitNullValueWeight]
 * - [ListValueSize]
 * - [NullNonNullableWeight]
 * - [ResolverExceptionWeight]
 */
fun arbRuntimeWiring(
    sdl: String,
    seed: Long,
    cfg: Config = Config.default
): RuntimeWiring = ArbRuntimeWiringGen(sdl, seed, cfg).gen()

private class ArbRuntimeWiringGen(
    sdl: String,
    private val seed: Long,
    private val cfg: Config
) {
    private val schema = viaduct.engine.api.ViaductSchema(sdl.asSchema)
    private val scalarArbs = ScalarRawValueArbs(cfg)

    fun gen(): RuntimeWiring {
        val wb = RuntimeWiring.newRuntimeWiring()
        schema.schema.typeMap.values.forEach {
            when (val t = it) {
                is GraphQLUnionType -> genTypeResolver(wb, t)
                is GraphQLInterfaceType -> genTypeResolver(wb, t)
                is GraphQLObjectType -> genDataFetcher(wb, t)
            }
        }

        return wb.build()
    }

    private fun genTypeResolver(
        wb: RuntimeWiring.Builder,
        type: GraphQLCompositeType
    ) {
        wb.type(type.name) { b ->
            b.typeResolver { env: TypeResolutionEnvironment ->
                val rs = saltedRandom(env.hash)
                maybeThrowResolverException(rs)
                val objectType = Arb.of(schema.rels.possibleObjectTypes(type).toList()).next(rs)

                // rels was built from a schema before it was wired up. The types may have been
                // transformed during wiring, which may cause the actual type that was returned
                // by rels to not match an existing reference in the runtime schema.
                // De-reference this type through the schema before returning.
                env.schema.getObjectType(objectType.name)
            }
        }
    }

    private fun genDataFetcher(
        wb: RuntimeWiring.Builder,
        type: GraphQLObjectType
    ) {
        wb.type(type.name) { builder ->
            type.fields.fold(builder) { b, f ->
                b.dataFetcher(f.name) { env: DataFetchingEnvironment ->
                    val rs = saltedRandom(env.hash)
                    maybeThrowResolverException(rs)
                    genValue(env.fieldType, rs)
                }
            }
        }
    }

    private fun maybeThrowResolverException(rs: RandomSource) {
        if (rs.sampleWeight(cfg[ResolverExceptionWeight])) {
            throw ResolverInjectedException()
        }
    }

    private fun genValue(
        type: GraphQLOutputType,
        rs: RandomSource
    ): Any? {
        if (type is GraphQLNonNull && rs.sampleWeight(cfg[NullNonNullableWeight])) {
            return null
        }
        if (type !is GraphQLNonNull && rs.sampleWeight(cfg[ExplicitNullValueWeight])) {
            return null
        }

        val unwrappedType = if (type is GraphQLNonNull) GraphQLTypeUtil.unwrapOneAs(type) else type
        return when (val t = unwrappedType) {
            is GraphQLList ->
                buildList {
                    repeat(Arb.int(cfg[ListValueSize]).next(rs)) {
                        add(genValue(GraphQLTypeUtil.unwrapOneAs(t), rs))
                    }
                }

            is GraphQLScalarType ->
                scalarArbs(t.name, rs).let { RawToKotlin.invoke(t, it) }

            is GraphQLEnumType ->
                Arb.of(t.values).next(rs).name

            else ->
                // return an empty map for interfaces, unions, and objects
                // interfaces/unions will be resolved through a type resolver into an object
                // for objects, because this wiring configures data fetchers for every field, the fields will be resolved
                // via graphql execution
                emptyMap<String, Any?>()
        }
    }

    /**
     * Returns a [RandomSource] that is guaranteed to produce the same values for the
     * configured [seed] and provided [salt] values.
     *
     * To provide deterministic value generation, [Arb]s used in this class should use a
     * salted [RandomSource], where the salt is derived from a "stable" property of the GraphQL
     * input. "stable" means that the value does not change between repeated executions of
     * the same GraphQL document and variables.
     *
     * Salts *should not* be derived from properties that change between executions of the
     * same document, like the value of [graphql.language.Node.hashCode], which is unique per
     * instance.
     *
     * [RandomSource]s returned from this method are safe to be used for reading from
     * multiple [Arb]'s provided that those usages can be deterministically ordered.
     * An example of an unsafe use is saving a reference to a salted random and using it
     * between multiple data fetchers (which may be invoked in non-deterministic orders),
     * or saving the reference for repeated use of the same DataFetcher if the schema is
     * expected to get parallel requests.
     */
    private fun saltedRandom(salt: Long): RandomSource = RandomSource.seeded(seed xor salt)

    private val MergedField.hash: Long get() =
        singleField.hash
    private val Field.hash: Long get() =
        resultKey.hashCode().toLong()
    private val SelectionSet.hash: Long get() =
        selections.mapNotNull { it as? Field }.fold(0L) { acc, f -> acc xor f.hash }
    private val ResultPath.hash: Long get() =
        toString().hashCode().toLong()
    private val DataFetchingEnvironment.hash: Long get() =
        arguments.hashCode().toLong() xor (field.selectionSet?.hash ?: 0L) xor executionStepInfo.path.hash
    private val TypeResolutionEnvironment.hash: Long get() =
        arguments.hashCode().toLong() xor field.hash
}

internal class ResolverInjectedException : Exception("Injected exception, see ResolverExceptionWeight")
