package viaduct.arbitrary.graphql

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.map
import viaduct.arbitrary.common.Config
import viaduct.graphql.schema.ViaductSchema as VSchema
import viaduct.graphql.schema.graphqljava.extensions.fromGraphQLSchema

/** Generate arbitrary instances of [viaduct.graphql.schema.ViaductSchema] from a static [Config]. */
fun Arb.Companion.vSchema(config: Config = Config.default): Arb<VSchema> = Arb.graphQLSchema(config).map { schema -> VSchema.fromGraphQLSchema(schema) }

/** Generate an arbitrary [viaduct.graphql.schema.ViaductSchema.TypeExpr]. */
fun Arb.Companion.vSchemaTypeExpr(config: Config = Config.default): Arb<VSchema.TypeExpr<*>> =
    vSchema(config)
        .filter { it.types.values.isNotEmpty() }
        .flatMap { schema ->
            Arb
                .element(schema.types.values)
                .flatMap {
                    val exprs =
                        when (val type = it) {
                            is VSchema.Record ->
                                type.fields.map { f -> f.type } + type.asTypeExpr()
                            else -> listOf(type.asTypeExpr())
                        }
                    Arb.element(exprs)
                }
        }
