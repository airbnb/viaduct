package viaduct.tenant.codegen.bytecode.config

import viaduct.graphql.schema.test.createSchema

fun withSchema(
    pkgName: String,
    sdl: String = "",
    block: WithSchema.() -> Unit
) = WithSchema(pkgName, sdl).block()

class WithSchema(
    val pkgName: String,
    val sdl: String,
) {
    val schema = createSchema(sdl)
    val baseTypeMapper = ViaductBaseTypeMapper(schema)
}
