package viaduct.engine.runtime

import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import org.intellij.lang.annotations.Language
import viaduct.engine.api.EngineSchema

fun createSchema(
    @Language("GraphQL") sdl: String
): EngineSchema {
    val tdr = SchemaParser().parse(sdl)
    return EngineSchema(SchemaGenerator().makeExecutableSchema(tdr, RuntimeWiring.MOCKED_WIRING))
}
