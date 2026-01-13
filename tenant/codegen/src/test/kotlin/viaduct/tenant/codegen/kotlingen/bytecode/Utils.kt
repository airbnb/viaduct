package viaduct.tenant.codegen.kotlingen.bytecode

import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import java.io.File
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.GJSchema
import viaduct.tenant.codegen.bytecode.config.ViaductBaseTypeMapper
import viaduct.utils.timer.Timer

fun mkSchema(sdl: String): ViaductSchema {
    val tdr = SchemaParser().parse(sdl)
    val schema = UnExecutableSchemaGenerator.makeUnExecutableSchema(tdr)
    return GJSchema.fromSchema(schema)
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
