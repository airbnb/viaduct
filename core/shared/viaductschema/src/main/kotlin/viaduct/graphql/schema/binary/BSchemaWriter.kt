package viaduct.graphql.schema.binary

import java.io.OutputStream
import viaduct.graphql.schema.ViaductSchema

internal fun writeBSchema(
    schema: ViaductSchema,
    dst: OutputStream
) {
    BOutputStream(dst).use { out ->
        val constantsEncoderBuilder = ConstantsEncoder.Builder()
        val schemaInfo = SchemaInfo(schema, constantsEncoderBuilder)
        val constantsEncoder = constantsEncoderBuilder.build()

        SchemaEncoder(out, schemaInfo, constantsEncoder).encode()
    }
}
