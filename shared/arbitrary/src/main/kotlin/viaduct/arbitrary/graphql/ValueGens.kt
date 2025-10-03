package viaduct.arbitrary.graphql

import graphql.schema.GraphQLSchema
import io.kotest.property.RandomSource
import viaduct.arbitrary.common.Config

/** A collection of high-level value generators */
internal class ValueGens(schema: GraphQLSchema, cfg: Config, rs: RandomSource) {
    private val refResolver = TypeReferenceResolver.fromSchema(schema)

    /** a generator for [viaduct.mapping.graphql.RawValue]'s */
    val raw = GJRawValueGen(refResolver, cfg, rs)

    /** a generator for [Value]'s, suitable for use with graphql-java APIs */
    val gj = raw.map(GJRawToGJ(refResolver))

    /**
     * A generator for kotlin values, sometimes referred to as "internal" by graphql-java
     * Suitable for use as variable values.
     */
    val kotlin = raw.map(RawToKotlin)
}
