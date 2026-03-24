package viaduct.engine.runtime

import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import org.intellij.lang.annotations.Language
import viaduct.engine.api.ViaductSchema

fun createSchema(
    @Language("GraphQL") sdl: String
): ViaductSchema {
    val tdr = SchemaParser().parse(sdl)
    return ViaductSchema(SchemaGenerator().makeExecutableSchema(tdr, RuntimeWiring.MOCKED_WIRING))
}
