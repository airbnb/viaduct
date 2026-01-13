package viaduct.graphql.schema.binary

import graphql.schema.idl.SchemaParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.GJSchemaRaw
import viaduct.graphql.schema.test.ViaductSchemaContract

/**
 * Runs the ViaductSchemaContract test suite on schemas that have been
 * round-tripped through the binary BSchema format. This validates that
 * the binary encoding/decoding preserves all behavioral properties
 * defined in the contract (default value handling, isOverride computation,
 * field path navigation, etc.).
 */
class BSchemaRoundTripContractTest : ViaductSchemaContract {
    override fun makeSchema(schema: String): ViaductSchema {
        val tdr = SchemaParser().parse(schema)
        val original = GJSchemaRaw.fromRegistry(tdr)

        // Round-trip through binary format
        val tmp = ByteArrayOutputStream()
        writeBSchema(original, tmp)
        return readBSchema(ByteArrayInputStream(tmp.toByteArray()))
    }
}
