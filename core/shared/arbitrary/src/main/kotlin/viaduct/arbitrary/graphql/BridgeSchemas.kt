package viaduct.arbitrary.graphql

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.map
import viaduct.apiannotations.VisibleForTest
import viaduct.arbitrary.common.Config
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromGraphQLSchema

/** Generate an Arb of [ViaductSchema] from Config */
fun Arb.Companion.viaductExtendedSchema(config: Config = Config.default,): Arb<ViaductSchema> = Arb.graphQLSchema(config).map { schema -> ViaductSchema.fromGraphQLSchema(schema) }

/** Generate an arbitrary [ViaductSchema.TypeExpr] */
@VisibleForTest
fun Arb.Companion.typeExpr(config: Config = Config.default,): Arb<ViaductSchema.TypeExpr<*>> =
    viaductExtendedSchema(config)
        .filter { it.types.values.isNotEmpty() }
        .flatMap { schema ->
            Arb
                .element(schema.types.values)
                .flatMap {
                    val exprs =
                        when (val type = it) {
                            is ViaductSchema.Record ->
                                type.fields.map { it.type } + type.asTypeExpr()
                            else -> listOf(type.asTypeExpr())
                        }
                    Arb.element(exprs)
                }
        }
