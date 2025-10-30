package viaduct.arbitrary.graphql

import io.kotest.property.Arb
import io.kotest.property.arbitrary.filter
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.arbitrary.common.checkInvariants
import viaduct.graphql.schema.checkBridgeSchemaInvariants

class ViaductSchemasTest : KotestPropertyBase() {
    @Test
    fun `generates valid ViaductSchemas`() =
        Arb.viaductExtendedSchema().checkInvariants(100) { schema, check ->
            checkBridgeSchemaInvariants(schema, check)
        }

    @Test
    fun `TypeExpr methods do not throw for non-list types`() =
        Arb.typeExpr()
            .filter { !it.isList }
            .checkInvariants(100) { type, check ->
                check.doesNotThrow("unexpected err") {
                    type.nullableAtDepth(0)
                    type.isList
                    type.listDepth
                }
            }
}
