package viaduct.tenant.codegen.kotlingen.bytecode

import graphql.schema.idl.SchemaParser
import java.io.File
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.tenant.codegen.bytecode.config.ViaductBaseTypeMapper
import viaduct.utils.timer.Timer

fun mkSchema(sdl: String): ViaductSchema {
    val tdr = SchemaParser().parse(sdl)
    return ViaductSchema.fromTypeDefinitionRegistry(tdr)
}

fun mkKotlinGRTFilesBuilder(
    schema: ViaductSchema,
    pkg: String = "pkg"
): KotlinGRTFilesBuilder {
    val builder = KotlinGRTFilesBuilderImpl(
        KotlinCodeGenArgs(
            pkg,
            File.createTempFile("kotlingrt_", null),
            Timer(),
            ViaductBaseTypeMapper(schema)
        )
    )
    builder.initSchemaForTest(schema)
    return builder
}
