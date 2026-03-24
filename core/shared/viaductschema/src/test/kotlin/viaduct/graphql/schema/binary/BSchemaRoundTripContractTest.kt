package viaduct.graphql.schema.binary

import graphql.schema.idl.SchemaParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.binary.extensions.fromBinaryFile
import viaduct.graphql.schema.binary.extensions.toBinaryFile
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.graphql.schema.test.ViaductSchemaContract

/**
 * Runs the ViaductSchemaContract test suite on schemas that have been
 * round-tripped through the binary BSchema format. This validates that
 * the binary encoding/decoding preserves all behavioral properties
 * defined in the contract (default value handling, isOverride computation,
 * field path navigation, etc.).
 *
 * Uses fromTypeDefinitionRegistry to avoid graphql-java validation bugs that would
 * incorrectly reject valid GraphQL SDL.
 */
class BSchemaRoundTripContractTest : ViaductSchemaContract {
    override fun createSchema(schema: String): ViaductSchema {
        val registry = SchemaParser().parse(schema)
        val original = ViaductSchema.fromTypeDefinitionRegistry(registry)

        // Round-trip through binary format
        val tmp = ByteArrayOutputStream()
        original.toBinaryFile(tmp)
        return ViaductSchema.fromBinaryFile(ByteArrayInputStream(tmp.toByteArray()))
    }
}
