package viaduct.tenant.codegen.kotlingen.bytecode

import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import java.io.File
import viaduct.graphql.schema.ViaductExtendedSchema
import viaduct.graphql.schema.graphqljava.GJSchema
import viaduct.tenant.codegen.bytecode.config.ViaductBaseTypeMapper
import viaduct.utils.timer.Timer

fun mkSchema(sdl: String): ViaductExtendedSchema {
    val tdr = SchemaParser().parse(sdl)
    val schema = UnExecutableSchemaGenerator.makeUnExecutableSchema(tdr)
    return GJSchema.fromSchema(schema)
}

fun mkKotlinGRTFilesBuilder(
    schema: ViaductExtendedSchema,
    pkg: String = "pkg"
): KotlinGRTFilesBuilder =
    KotlinGRTFilesBuilderImpl(
        KotlinCodeGenArgs(
            pkg,
            File.createTempFile("kotlingrt_", null),
            Timer(),
            ViaductBaseTypeMapper(schema)
        )
    )
