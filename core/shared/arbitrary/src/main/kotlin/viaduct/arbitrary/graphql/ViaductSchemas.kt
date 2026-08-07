package viaduct.arbitrary.graphql

import io.kotest.property.Arb
import io.kotest.property.arbitrary.map
import viaduct.arbitrary.common.Config
import viaduct.engine.api.ViaductSchema

/** Generate arbitrary instances of [viaduct.engine.api.ViaductSchema] from a static [Config]. */
fun Arb.Companion.viaductSchema(cfg: Config = Config.default): Arb<ViaductSchema> = graphQLSchema(cfg).map(::ViaductSchema)
