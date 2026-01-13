package viaduct.arbitrary.graphql

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.map
import viaduct.apiannotations.TestingApi
import viaduct.arbitrary.common.Config
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.GJSchema
import viaduct.graphql.schema.graphqljava.ValueConverter

/** Generate an Arb of [ViaductSchema] from Config */
fun Arb.Companion.viaductExtendedSchema(
    config: Config = Config.default,
    valueConverter: ValueConverter = ValueConverter.default
): Arb<ViaductSchema> = Arb.graphQLSchema(config).map { schema -> GJSchema.fromSchema(schema, valueConverter) }

/** Generate an arbitrary [ViaductSchema.TypeExpr] */
@TestingApi
fun Arb.Companion.typeExpr(
    config: Config = Config.default,
    valueConverter: ValueConverter = ValueConverter.default
): Arb<ViaductSchema.TypeExpr> =
    viaductExtendedSchema(config, valueConverter)
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
